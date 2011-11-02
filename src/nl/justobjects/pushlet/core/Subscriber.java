// Copyright (c) 2000 Just Objects B.V. <just@justobjects.nl>
// Distributable under LGPL license. See terms of license at gnu.org.

package nl.justobjects.pushlet.core;

import java.io.UnsupportedEncodingException;

import nl.justobjects.pushlet.redis.RedisManager;
import nl.justobjects.pushlet.util.PushletException;
import nl.justobjects.pushlet.util.Rand;
import nl.justobjects.pushlet.util.Sys;

/**
 * Handles data channel between dispatcher and client.
 * 
 * @author Just van den Broecke - Just Objects &copy;
 * @version $Id: Subscriber.java,v 1.26 2007/11/23 14:33:07 justb Exp $
 */
public class Subscriber implements Protocol, ConfigDefs {
  static RedisManager redis = RedisManager.getInstance();
  public static final String REDIS_SUBSCRIBER_PREFIX = "pushlet:subscriber:";
  public static final String REDIS_SUBSCRIPTIONS_PREFIX = "pushlet:subscriptions:";
  private String myHkey;
  private String subscriptionsHkey;

  /**
   * URL to be used in refresh requests in pull/poll modes.
   */
  private long queueReadTimeoutMillis = Config.getLongProperty(QUEUE_READ_TIMEOUT_MILLIS);
  private long queueWriteTimeoutMillis = Config.getLongProperty(QUEUE_WRITE_TIMEOUT_MILLIS);
  private long refreshTimeoutMillis = Config.getLongProperty(PULL_REFRESH_TIMEOUT_MILLIS);
  volatile long lastAlive = Sys.now();

  private Session session;

  /**
   * Blocking queue.
   */
  private EventQueue eventQueue;

  /**
   * Are we able to accept/send events ?.
   */
  private volatile boolean active;

  /**
   * Transfer mode (stream, pull, poll).
   */
  private String mode;

  /**
   * Protected constructor as we create through factory method.
   */
  protected Subscriber() {
  }

  /**
   * Create instance through factory method.
   * 
   * @param aSession
   *          the parent Session
   * @return a Subscriber object (or derived)
   * @throws PushletException
   *           exception, usually misconfiguration
   */
  public static Subscriber create(Session aSession) throws PushletException {
    Subscriber subscriber;
    try {
      subscriber = (Subscriber) Config.getClass(SUBSCRIBER_CLASS, "nl.justobjects.pushlet.core.Subscriber").newInstance();
    } catch (Throwable t) {
      throw new PushletException("Cannot instantiate Subscriber from config", t);
    }

    subscriber.session = aSession;
    subscriber.myHkey = REDIS_SUBSCRIBER_PREFIX + aSession.getId();
    subscriber.subscriptionsHkey = REDIS_SUBSCRIPTIONS_PREFIX + aSession.getId();
    subscriber.eventQueue = new EventQueue(aSession.getId(), Config.getIntProperty(QUEUE_SIZE));

    if (subscriber.isPersistence()) {
      subscriber.readStatus();
    }
    subscriber.saveStatus();

    return subscriber;
  }

  public void setActive(boolean status) {
    active = status;
  }

  public void start() {
    active = true;
  }

  public void stop() {
    eventQueue.clear(); //@wjw_add 在停止时要清除事件队列

    redis.del(myHkey); //清除redis里的subscriber
    removeSubscriptions(); //清除redis里的subscriptions
    active = false;
  }

  //@wjw_node 在出现意外情况时,被调用,在此方法里停止session
  //TODO@SNS 在处理SNS时,应该屏蔽
  public void bailout() {
    session.stop();
  }

  /**
   * Are we still active to handle events.
   */
  public boolean isActive() {
    return active;
  }

  /**
   * Return client session.
   */
  public Session getSession() {
    return session;
  }

  /**
   * Get (session) id.
   */
  public String getId() {
    return session.getId();
  }

  /**
   * Return subscriptions.
   */
  public Subscription[] getSubscriptions() {
    java.util.List<byte[]> bbSubscription = redis.hvals(subscriptionsHkey);
    Subscription[] tempSubscriptions = new Subscription[bbSubscription.size()];
    int i = 0;
    for (byte[] bb : bbSubscription) {
      try {
        tempSubscriptions[i] = (Subscription) redis.fromXML(new String(bb, RedisManager.REDIS_CHARSET));
      } catch (UnsupportedEncodingException e) {
      }
      i++;
    }

    return tempSubscriptions;
  }

  /**
   * Add a subscription.
   */
  public Subscription addSubscription(String aSubject, String aLabel) throws PushletException {
    Subscription subscription = Subscription.create(aSubject, aLabel);
    redis.hset(subscriptionsHkey, subscription.getId(), redis.toXML(subscription));
    info("Subscription added subject=" + aSubject + " sid=" + subscription.getId() + " label=" + aLabel);
    return subscription;
  }

  /**
   * Remove a subscription.
   */
  public Subscription removeSubscription(String aSubscriptionId) {
    Subscription subscription = null;

    String strObj = redis.hget(subscriptionsHkey, aSubscriptionId);
    if (strObj == null) {
      subscription = null;
    } else {
      subscription = (Subscription) redis.fromXML(strObj);
      redis.hdel(subscriptionsHkey, aSubscriptionId);
    }

    if (subscription == null) {
      warn("No subscription found sid=" + aSubscriptionId);
      return null;
    }
    info("Subscription removed subject=" + subscription.getSubject() + " sid=" + subscription.getId() + " label="
        + subscription.getLabel());
    return subscription;
  }

  /**
   * Remove all subscriptions.
   */
  public void removeSubscriptions() {
    redis.del(subscriptionsHkey);
  }

  public String getMode() {
    return mode;
  }

  public void setMode(String aMode) {
    mode = aMode;
    redis.hset(myHkey, "mode", mode);
  }

  public long getRefreshTimeMillis() {
    String minWaitProperty = PULL_REFRESH_WAIT_MIN_MILLIS;
    String maxWaitProperty = PULL_REFRESH_WAIT_MAX_MILLIS;
    if (mode.equals((MODE_POLL))) {
      minWaitProperty = POLL_REFRESH_WAIT_MIN_MILLIS;
      maxWaitProperty = POLL_REFRESH_WAIT_MAX_MILLIS;

    }
    return Rand.randomLong(Config.getLongProperty(minWaitProperty), Config.getLongProperty(maxWaitProperty));
  }

  //TODO@fetchEvents 关键:Get events from queue and push to client.
  /**
   * Get events from queue and push to client.
   */
  public void fetchEvents(Command aCommand) throws PushletException {
    String refreshURL = aCommand.httpReq.getRequestURI() + "?" + P_ID + "=" + session.getId() + "&" + P_EVENT + "="
        + E_REFRESH;

    // This is the only thing required to support "poll" mode
    if (mode.equals(MODE_POLL)) {
      queueReadTimeoutMillis = 0;
      refreshTimeoutMillis = Config.getLongProperty(POLL_REFRESH_TIMEOUT_MILLIS);
    }

    // Required for fast bailout (tomcat)
    aCommand.httpRsp.setBufferSize(128);

    // Try to prevent caching in any form.
    aCommand.sendResponseHeaders();

    // Let clientAdapter determine how to send event
    ClientAdapter clientAdapter = aCommand.getClientAdapter();
    Event responseEvent = aCommand.getResponseEvent();
    try {
      clientAdapter.start();

      // Send first event (usually hb-ack or listen-ack)
      clientAdapter.push(responseEvent);

      // In pull/poll mode and when response is listen-ack or join-listen-ack,
      // return and force refresh immediately
      // such that the client recieves response immediately over this channel.
      // This is usually when loading the browser app for the first time
      if ((mode.equals(MODE_POLL) || mode.equals(MODE_PULL))
          && responseEvent.getEventType().endsWith(Protocol.E_LISTEN_ACK)) {
        sendRefresh(clientAdapter, refreshURL);

        // We should come back later with refresh event...
        return;
      }
    } catch (Throwable t) {
      bailout();
      return;
    }

    Event[] events = null;

    // Main loop: as long as connected, get events and push to client
    long eventSeqNr = 1;
    while (isActive()) {
      // Indicate we are still alive
      lastAlive = Sys.now();

      // Update session time to live
      session.kick();

      // Get next events; blocks until timeout or entire contents
      // of event queue is returned. Note that "poll" mode
      // will return immediately when queue is empty.
      try {
        // Put heartbeat in queue when starting to listen in stream mode
        // This speeds up the return of *_LISTEN_ACK
        if (mode.equals(MODE_STREAM) && eventSeqNr == 1) {
          eventQueue.enQueue(new Event(E_HEARTBEAT));
        }

        events = eventQueue.deQueueAll(queueReadTimeoutMillis);
      } catch (InterruptedException ie) {
        warn("interrupted");
        bailout();
      }

      // Send heartbeat when no events received
      if (events == null) {
        events = new Event[1];
        events[0] = new Event(E_HEARTBEAT);
      }

      // ASSERT: one or more events available

      // Send events to client using adapter
      // debug("received event count=" + events.length);
      for (int i = 0; i < events.length; i++) {
        // Check for abort event
        if (events[i].getEventType().equals(E_ABORT)) {
          warn("Aborting Subscriber");
          bailout();
        }

        // Push next Event to client
        try {
          // Set sequence number
          events[i].setField(P_SEQ, eventSeqNr++);

          // Push to client through client adapter
          clientAdapter.push(events[i]);
        } catch (Throwable t) {
          bailout();
          return;
        }
      }

      // Force client refresh request in pull or poll modes
      if (mode.equals(MODE_PULL) || mode.equals(MODE_POLL)) {
        sendRefresh(clientAdapter, refreshURL); //@wjw_node 在此方法内部会调用aClientAdapter.stop();来停止输出

        // Always leave loop in pull/poll mode
        break;
      }
    }
  }

  /**
   * Determine if we should receive event.
   */
  public Subscription match(Event event) {
    Subscription[] subscriptions = getSubscriptions();
    for (int i = 0; i < subscriptions.length; i++) {
      if (subscriptions[i].match(event)) { //@wjw_node 使用String.startsWith()来判断
        return subscriptions[i];
      }
    }
    return null;
  }

  /**
   * Event from Dispatcher: enqueue it.
   */
  public void onEvent(Event theEvent) {
    if (!isActive()) {
      return;
    }

    // p("send: queue event: "+theEvent.getSubject());

    // Check if we had any active continuation for at
    // least 'timeOut' millisecs. If the client has left this
    // instance there would be no way of knowing otherwise.
    long now = Sys.now();
    if (now - lastAlive > refreshTimeoutMillis) {
      warn("not alive for at least: " + refreshTimeoutMillis + "ms, leaving...");
      bailout();
      return;
    }

    // Put event in queue; leave if queue full
    try {
      if (!eventQueue.enQueue(theEvent, queueWriteTimeoutMillis)) {
        warn("queue full, bailing out...");
        bailout(); //TODO@ 是否要忽略此类异常???
      }

      // ASSERTION : Event in queue.
      // see fetchEvents() where Events are dequeued and pushed to the client.
    } catch (InterruptedException ie) {
      bailout();
    }

  }

  /**
   * Send refresh command to pull/poll clients.
   */
  protected void sendRefresh(ClientAdapter aClientAdapter, String aRefreshURL) {
    Event refreshEvent = new Event(E_REFRESH);

    // Set wait time and url for refresh
    refreshEvent.setField(P_WAIT, "" + getRefreshTimeMillis());
    refreshEvent.setField(P_URL, aRefreshURL);

    try {
      // Push to client through client adapter
      aClientAdapter.push(refreshEvent);

      // Stop this round until refresh event
      aClientAdapter.stop();
    } catch (Throwable t) {
      // Leave on any exception
      bailout();
    }
  }

  /**
   * Info.
   */
  protected void info(String s) {
    session.info("[Subscriber] " + s);
  }

  /**
   * Exceptional print util.
   */
  protected void warn(String s) {
    session.warn("[Subscriber] " + s);
  }

  /**
   * Exceptional print util.
   */
  protected void debug(String s) {
    session.debug("[Subscriber] " + s);
  }

  public String toString() {
    return session.toString();
  }

  public boolean isPersistence() {
    return redis.exists(myHkey);
  }

  public void saveStatus() {
    if (mode != null) {
      redis.hset(myHkey, "mode", mode);
    }
  }

  public void readStatus() {
    mode = redis.hget(myHkey, "mode");
  }

}
