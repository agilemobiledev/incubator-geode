/*
 * =========================================================================
 *  Copyright (c) 2002-2014 Pivotal Software, Inc. All Rights Reserved.
 *  This product is protected by U.S. and international copyright
 *  and intellectual property laws. Pivotal products are covered by
 *  more patents listed at http://www.pivotal.io/patents.
 * ========================================================================
 */
package com.gemstone.gemfire.management.internal.cli.shell;

import java.io.IOException;
import java.net.MalformedURLException;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.JMX;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.management.QueryExp;
import javax.management.ReflectionException;
import javax.management.remote.JMXConnectionNotification;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.rmi.ssl.SslRMIClientSocketFactory;

import com.gemstone.gemfire.internal.util.ArrayUtils;
import com.gemstone.gemfire.management.DistributedSystemMXBean;
import com.gemstone.gemfire.management.MemberMXBean;
import com.gemstone.gemfire.management.internal.MBeanJMXAdapter;
import com.gemstone.gemfire.management.internal.ManagementConstants;
import com.gemstone.gemfire.management.internal.cli.CommandRequest;
import com.gemstone.gemfire.management.internal.cli.LogWrapper;

/**
 * OperationInvoker JMX Implementation
 *
 * @author Abhishek Chaudhari
 *
 * @since 7.0
 */
public class JmxOperationInvoker implements OperationInvoker {

  public static final String JMX_URL_FORMAT = "service:jmx:rmi://{0}/jndi/rmi://{0}:{1}/jmxrmi";

  // an JMX object describing the client-end of a JMX connection
  private JMXConnector connector;

  // address of the JMX Connector Server
  private JMXServiceURL url;

  // an instance of an MBeanServer connection (in a connected state)
  private MBeanServerConnection mbsc;

  // String representation of the GemFire JMX Manager endpoint, including host and port
  private String endpoints;

  // the host and port of the GemFire Manager
  private String managerHost;
  private int managerPort;

  // MBean Proxies
  private DistributedSystemMXBean distributedSystemMXBeanProxy;
  private MemberMXBean memberMXBeanProxy;

  private ObjectName managerMemberObjectName;

  /*package*/ final AtomicBoolean isConnected = new AtomicBoolean(false);
  /*package*/ final AtomicBoolean isSelfDisconnect = new AtomicBoolean(false);

  private int clusterId = CLUSTER_ID_WHEN_NOT_CONNECTED;

  public JmxOperationInvoker(final String host,
                             final int port,
                             final String userName,
                             final String password,
                             final Map<String, String> sslConfigProps)
    throws Exception
  {
    final Set<String> propsToClear = new TreeSet<String>();
    try {
      this.managerHost = host;
      this.managerPort = port;
      this.endpoints = host + "[" + port + "]"; // Use the same syntax as the "connect" command.

      // Modify check period from default (60 sec) to 1 sec
      final Map<String, Object> env = new HashMap<String, Object>();

      env.put(JMXConnectionListener.CHECK_PERIOD_PROP, JMXConnectionListener.CHECK_PERIOD);

      if (userName != null && userName.length() > 0) {
        env.put(JMXConnector.CREDENTIALS, new String[] { userName, password });
      }      
      Set<Entry<String, String>> entrySet = sslConfigProps.entrySet();
      for (Iterator<Entry<String, String>> it = entrySet.iterator(); it.hasNext();) {
        Entry<String, String> entry = it.next();
        String key = entry.getKey();        
        if (key.startsWith("javax.") || key.startsWith("cluster-ssl") || key.startsWith("jmx-manager-ssl") ) {
          key =  checkforSystemPropertyPrefix(entry.getKey());
          //TODO : If protocol is any lookup and set one of the following : Copied from SocketCreator 
          // String[] knownAlgorithms = {"SSL", "SSLv2", "SSLv3", "TLS", "TLSv1", "TLSv1.1"};
          System.setProperty(key, entry.getValue());
          propsToClear.add(key);
        }
      }

      if(!sslConfigProps.isEmpty()){
        if (System.getProperty(Gfsh.SSL_KEYSTORE) != null || System.getProperty(Gfsh.SSL_TRUSTSTORE) != null) {
          // use ssl to connect
          env.put("com.sun.jndi.rmi.factory.socket", new SslRMIClientSocketFactory());
        }
      }


      this.url = new JMXServiceURL(MessageFormat.format(JMX_URL_FORMAT, checkAndConvertToCompatibleIPv6Syntax(host), String.valueOf(port)));      
      this.connector = JMXConnectorFactory.connect(url, env);
      this.mbsc = connector.getMBeanServerConnection();
      this.connector.addConnectionNotificationListener(new JMXConnectionListener(this), null, null);
      this.connector.connect(); // TODO this call to connect is not needed
      this.distributedSystemMXBeanProxy = JMX.newMXBeanProxy(mbsc, MBeanJMXAdapter.getDistributedSystemName(), DistributedSystemMXBean.class);

      if (this.distributedSystemMXBeanProxy == null || !JMX.isMXBeanInterface(DistributedSystemMXBean.class)) {
        LogWrapper.getInstance().info("DistributedSystemMXBean is not present on member with endpoints : "+this.endpoints);
        connector.close();
        throw new JMXConnectionException(JMXConnectionException.MANAGER_NOT_FOUND_EXCEPTION);
      }
      else {
        this.managerMemberObjectName = this.distributedSystemMXBeanProxy.getMemberObjectName();
        if (this.managerMemberObjectName == null || !JMX.isMXBeanInterface(MemberMXBean.class)) {
          LogWrapper.getInstance().info("MemberMXBean with ObjectName "+this.managerMemberObjectName+" is not present on member with endpoints : "+endpoints);
          this.connector.close();
          throw new JMXConnectionException(JMXConnectionException.MANAGER_NOT_FOUND_EXCEPTION);
        }
        else {
          this.memberMXBeanProxy = JMX.newMXBeanProxy(mbsc, managerMemberObjectName, MemberMXBean.class);
        }
      }

      this.isConnected.set(true);
      this.clusterId = distributedSystemMXBeanProxy.getDistributedSystemId();
    }
    catch (NullPointerException e) {
      throw e;
    }
    catch (MalformedURLException e) {
      throw e;
    }
    catch (IOException e) {
      throw e;
    }
    finally {
      for (String propToClear : propsToClear) {
        System.clearProperty(propToClear);
      }
    }
  }

  
  private String checkforSystemPropertyPrefix(String key) {
    String returnKey = key;
    if (key.startsWith("javax."))
      returnKey = key;
    if (key.startsWith("cluster-ssl") || key.startsWith("jmx-manager-ssl")) {
      if (key.endsWith("keystore")) {
        returnKey = Gfsh.SSL_KEYSTORE;
      } else if (key.endsWith("keystore-password")) {
        returnKey = Gfsh.SSL_KEYSTORE_PASSWORD;
      } else if (key.endsWith("ciphers")) {
        returnKey = Gfsh.SSL_ENABLED_CIPHERS;
      } else if (key.endsWith("truststore-password")) {
        returnKey = Gfsh.SSL_TRUSTSTORE_PASSWORD;
      } else if (key.endsWith("truststore")) {
        returnKey = Gfsh.SSL_TRUSTSTORE;
      } else if (key.endsWith("protocols")) {
        returnKey = Gfsh.SSL_ENABLED_PROTOCOLS;
      }
    }    
    return returnKey;    
  }

  @Override
  public Object getAttribute(String resourceName, String attributeName) throws JMXInvocationException {
    try {
      return mbsc.getAttribute(ObjectName.getInstance(resourceName), attributeName);
    } catch (AttributeNotFoundException e) {
      throw new JMXInvocationException(attributeName + " not found for " + resourceName, e);
    } catch (InstanceNotFoundException e) {
      throw new JMXInvocationException(resourceName + " is not registered in the MBean server.", e);
    } catch (MalformedObjectNameException e) {
      throw new JMXInvocationException(resourceName + " is not a valid resource name.", e);
    } catch (MBeanException e) {
      throw new JMXInvocationException("Exception while fetching " + attributeName + " for " + resourceName, e);
    } catch (ReflectionException e) {
      throw new JMXInvocationException("Couldn't find "+attributeName+" for " + resourceName, e);
    } catch (NullPointerException e) {
      throw new JMXInvocationException("Given resourceName is null.", e);
    } catch (IOException e) {
      throw new JMXInvocationException(resourceName + " is not a valid resource name.", e);
    }
  }

  @Override
  public Object invoke(String resourceName, String operationName, Object[] params, String[] signature) throws JMXInvocationException {
    try {
      return invoke(ObjectName.getInstance(resourceName), operationName, params, signature);
    } catch (MalformedObjectNameException e) {
      throw new JMXInvocationException(resourceName + " is not a valid resource name.", e);
    } catch (NullPointerException e) {
      throw new JMXInvocationException("Given resourceName is null.", e);
    }
  }

  /**
   * JMX Specific operation invoke caller.
   *
   * @param resource
   * @param operationName
   * @param params
   * @param signature
   * @return result of JMX Operation invocation
   * @throws JMXInvocationException
   */
  protected Object invoke(ObjectName resource, String operationName, Object[] params, String[] signature) throws JMXInvocationException {
    try {
      return mbsc.invoke(resource, operationName, params, signature);
    } catch (InstanceNotFoundException e) {
      throw new JMXInvocationException(resource + " is not registered in the MBean server.", e);
    } catch (MBeanException e) {
      throw new JMXInvocationException("Exception while invoking " + operationName + " on " + resource, e);
    } catch (ReflectionException e) {
      throw new JMXInvocationException("Couldn't find "+operationName+" on " + resource + " with arguments "+Arrays.toString(signature), e);
    } catch (IOException e) {
      throw new JMXInvocationException("Couldn't communicate with remote server at " + toString(), e);
    }
  }

  public Set<ObjectName> queryNames(final ObjectName objectName, final QueryExp queryExpression) {
    try {
      return getMBeanServerConnection().queryNames(objectName, queryExpression);
    } catch (IOException e) {
      throw new JMXInvocationException(String.format("Failed to communicate with the remote MBean server at (%1$s)!",
        toString()), e);
    }
  }

  @Override
  public Object processCommand(final CommandRequest commandRequest) throws JMXInvocationException {
    //Gfsh.getCurrentInstance().printAsSevere(String.format("Command (%1$s)%n", commandRequest.getInput()));
    if (commandRequest.hasFileData()) {
      return memberMXBeanProxy.processCommand(commandRequest.getInput(), commandRequest.getEnvironment(),
        ArrayUtils.toByteArray(commandRequest.getFileData()));
    }
    else {
      return memberMXBeanProxy.processCommand(commandRequest.getInput(), commandRequest.getEnvironment());
    }
  }

  @Override
  public void stop() {
    try {
      this.isSelfDisconnect.set(true);
      this.connector.close();
      this.isConnected.set(false);
    } catch (IOException e) {
      // ignore exceptions occurring while closing the connector
    }
  }

  @Override
  public boolean isConnected() {
    return this.isConnected.get();
  }

  public DistributedSystemMXBean getDistributedSystemMXBean() {
    if (distributedSystemMXBeanProxy == null) {
      throw new IllegalStateException("The DistributedSystemMXBean proxy was not initialized properly!");
    }
    return distributedSystemMXBeanProxy;
  }

  public JMXServiceURL getJmxServiceUrl() {
    return this.url;
  }

  public String getManagerHost() {
    return managerHost;
  }

  public int getManagerPort() {
    return managerPort;
  }

  public <T> T getMBeanProxy(final ObjectName objectName, final Class<T> mbeanInterface) {
    if (DistributedSystemMXBean.class.equals(mbeanInterface)
      && ManagementConstants.OBJECTNAME__DISTRIBUTEDSYSTEM_MXBEAN.equals(objectName.toString())) {
      return mbeanInterface.cast(getDistributedSystemMXBean());
    }
    else if (JMX.isMXBeanInterface(mbeanInterface)) {
      return JMX.newMXBeanProxy(getMBeanServerConnection(), objectName, mbeanInterface);
    }
    else {
      return JMX.newMBeanProxy(getMBeanServerConnection(), objectName, mbeanInterface);
    }
  }

  public MBeanServerConnection getMBeanServerConnection() {
    if (this.mbsc == null) {
      throw new IllegalStateException("Gfsh is not connected to the GemFire Manager.");
    }
    return this.mbsc;
  }

  public boolean isReady() {
    try {
      return this.mbsc.isRegistered(managerMemberObjectName);
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public String toString() {
    return this.endpoints;
  }

  public int getClusterId() {
    return this.clusterId;
  }

  /*package*/ void resetClusterId() {
    clusterId = CLUSTER_ID_WHEN_NOT_CONNECTED;
  }

  /**
   * If the given host address contains a ":", considers it as an IPv6 address &
   * returns the host based on RFC2732 requirements i.e. surrounds the given
   * host address string with square brackets. If ":" is not found in the given
   * string, simply returns the same string.
   *
   * @param hostAddress
   *          host address to check if it's an IPv6 address
   * @return for an IPv6 address returns compatible host address otherwise
   *         returns the same string
   */
  //TODO - Abhishek: move to utility class
  // Taken from GFMon
  public static String checkAndConvertToCompatibleIPv6Syntax(String hostAddress) {
    // if host string contains ":", considering it as an IPv6 Address
    // Conforming to RFC2732 - http://www.ietf.org/rfc/rfc2732.txt
    if (hostAddress.indexOf(":") != -1) {
      LogWrapper logger = LogWrapper.getInstance();
      if (logger.fineEnabled()) {
        logger.fine("IPv6 host address detected, using IPv6 syntax for host in JMX connection URL");
      }
      hostAddress = "[" + hostAddress + "]";
      if (logger.fineEnabled()) {
        logger.fine("Compatible host address is : " + hostAddress);
      }
    }
    return hostAddress;
  }
}

/**
 * A Connection Notification Listener. Notifies Gfsh when a connection gets
 * terminated abruptly.
 *
 * @author Abhishek Chaudhari
 * @since 7.0
 */
class JMXConnectionListener implements NotificationListener {
  public static final String CHECK_PERIOD_PROP = "jmx.remote.x.client.connection.check.period";
  public static final long CHECK_PERIOD        = 1000L;
  private JmxOperationInvoker invoker;

  JMXConnectionListener (JmxOperationInvoker invoker) {
    this.invoker = invoker;
  }
  @Override
  public void handleNotification(Notification notification, Object handback) {
    if (JMXConnectionNotification.class.isInstance(notification)) {
      JMXConnectionNotification connNotif = (JMXConnectionNotification)notification;
      if (JMXConnectionNotification.CLOSED.equals(connNotif.getType()) ||
          JMXConnectionNotification.FAILED.equals(connNotif.getType())) {
        this.invoker.isConnected.set(false);
        this.invoker.resetClusterId();
        if (!this.invoker.isSelfDisconnect.get()) {
          Gfsh.getCurrentInstance().notifyDisconnect(this.invoker.toString());
        }
      }
    }
  }

}
