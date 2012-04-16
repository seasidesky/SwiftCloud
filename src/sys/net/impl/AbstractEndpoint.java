package sys.net.impl;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

import sys.net.api.Endpoint;
import sys.net.api.MessageHandler;

abstract public class AbstractEndpoint implements Endpoint {

	protected Object locator;
	transient protected MessageHandler handler;
	transient protected InetSocketAddress tcpAddress;

	protected AbstractEndpoint() {
	}

	protected AbstractEndpoint(final long locator) {
		this.locator = locator;
	}

	protected AbstractEndpoint(final InetSocketAddress saddr) {
		this(encodeLocator(saddr));
	}

	protected AbstractEndpoint(final String host, final int tcpPort) {
		this(new InetSocketAddress(host, tcpPort));
	}

	public InetSocketAddress tcpAddress() {
		if (tcpAddress == null) {
			tcpAddress = decodeLocator((Long) locator);
		}
		return tcpAddress;
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T locator() {
		return (T) locator;
	}

	@Override
	public int hashCode() {
		return tcpAddress().hashCode();
	}

	public boolean equals(AbstractEndpoint other) {
		return tcpAddress().equals(other.tcpAddress());
	}

	@Override
	public boolean equals(Object other) {
		return other != null && equals((AbstractEndpoint) other);
	}

	@Override
	public void setHandler(MessageHandler handler) {
		this.handler = handler;
	}

	@Override
	public MessageHandler getHandler() {
		return handler;
	}

	@Override
	public String toString() {
		return String.format("tcp://%s:%d", tcpAddress().getAddress().getHostAddress(), tcpAddress().getPort());
	}

	protected static long encodeLocator(InetSocketAddress saddr) {
		return encodeLocator(saddr.getAddress(), saddr.getPort());
	}

	protected static long encodeLocator(InetAddress ip, int port) {
		return ((long) ByteBuffer.wrap(ip.getAddress()).getInt() << Integer.SIZE) | port;
	}

	protected static InetSocketAddress decodeLocator(long locator) {
		try {
			ByteBuffer buf = ByteBuffer.allocate(Integer.SIZE / Byte.SIZE).putInt((int) (locator >> Integer.SIZE));
			return new InetSocketAddress(InetAddress.getByAddress(buf.array()), (int) (locator & 0x0FFFF));
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		return null;
	}
}