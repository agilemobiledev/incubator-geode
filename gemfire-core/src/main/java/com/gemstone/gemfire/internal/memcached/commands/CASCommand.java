/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package com.gemstone.gemfire.internal.memcached.commands;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.memcached.Reply;
import com.gemstone.gemfire.internal.memcached.RequestReader;
import com.gemstone.gemfire.internal.memcached.ValueWrapper;
import com.gemstone.gemfire.memcached.GemFireMemcachedServer.Protocol;

/**
 * "cas" is a check and set operation which means "store this data but
 * only if no one else has updated since I last fetched it."
 * 
 * @author Swapnil Bawaskar
 *
 */
public class CASCommand extends AbstractCommand {

  @Override
  public ByteBuffer processCommand(RequestReader request, Protocol protocol, Cache cache) {
    if (protocol == Protocol.ASCII) {
      return processAsciiCommand(request.getRequest(), cache);
    }
    // binary protocol has cas in regular add/put commands
    throw new IllegalStateException();
  }

  private ByteBuffer processAsciiCommand(ByteBuffer buffer, Cache cache) {
    CharBuffer flb = getFirstLineBuffer();
    getAsciiDecoder().reset();
    getAsciiDecoder().decode(buffer, flb, false);
    flb.flip();
    String firstLine = getFirstLine();
    String[] firstLineElements = firstLine.split(" ");
    
    String key = firstLineElements[1];
    int flags = Integer.parseInt(firstLineElements[2]);
    long expTime = Long.parseLong(firstLineElements[3]);
    int numBytes = Integer.parseInt(firstLineElements[4]);
    long casVersion = Long.parseLong(stripNewline(firstLineElements[5]));
    
    byte[] value = new byte[numBytes];
    buffer.position(firstLine.length());
    for (int i=0; i<numBytes; i++) {
      value[i] = buffer.get();
    }
    
    String reply = Reply.EXISTS.toString();
    Region<Object, ValueWrapper> r = getMemcachedRegion(cache);
    ValueWrapper expected = ValueWrapper.getDummyValue(casVersion);
    if (r.replace(key, expected, ValueWrapper.getWrappedValue(value, flags))) {
      reply = Reply.STORED.toString();
    }
    
    return asciiCharset.encode(reply);
  }
}
