/*
 * Copyright (c) 2000, 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.nio.ch;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.*;
import java.nio.channels.*;
import java.nio.channels.spi.*;
import java.util.*;
import sun.net.NetHooks;


/**
 * An implementation of ServerSocketChannels
 */

class ServerSocketChannelImpl
    extends ServerSocketChannel
    implements SelChImpl
{

    // Used to make native close and configure calls
    private static NativeDispatcher nd;

    // Our file descriptor
    // channel对应的文件描述符
    private final FileDescriptor fd;

    // fd value needed for dev/poll. This value will remain valid
    // even after the value in the file descriptor object has been set to -1
    // 文件描述符的具体值
    private int fdVal;

    // ID of native thread currently blocked in this channel, for signalling
    private volatile long thread = 0;

    // Lock held by thread currently blocked in this channel
    private final Object lock = new Object();

    // Lock held by any thread that modifies the state fields declared below
    // DO NOT invoke a blocking I/O operation while holding this lock!
    // 更新state的线程需要持有这个锁
    private final Object stateLock = new Object();

    // -- The following fields are protected by stateLock

    // Channel state, increases monotonically
    private static final int ST_UNINITIALIZED = -1;
    private static final int ST_INUSE = 0;
    private static final int ST_KILLED = 1;
    private int state = ST_UNINITIALIZED;

    // Binding
    private InetSocketAddress localAddress; // null => unbound

    // set true when exclusive binding is on and SO_REUSEADDR is emulated
    private boolean isReuseAddress;

    // Our socket adaptor, if any
    // socket适配器
    ServerSocket socket;

    // -- End of fields protected by stateLock


    ServerSocketChannelImpl(SelectorProvider sp) throws IOException {
        // 将selectorProvider给父类AbstractSelectableChannel持有
        super(sp);
        // 获取一个tcp链接相关的文件描述符对象持有
        this.fd =  Net.serverSocket(true);
        // 获取FileDescriptor持有的实际的fd属性值
        this.fdVal = IOUtil.fdVal(fd);
        // 将状态设置为INUSE，表示正在使用
        this.state = ST_INUSE;
    }

    ServerSocketChannelImpl(SelectorProvider sp,
                            FileDescriptor fd,
                            boolean bound)
        throws IOException
    {
        super(sp);
        this.fd =  fd;
        this.fdVal = IOUtil.fdVal(fd);
        this.state = ST_INUSE;
        if (bound)
            localAddress = Net.localAddress(fd);
    }

    public ServerSocket socket() {
        synchronized (stateLock) {
            if (socket == null)
                socket = ServerSocketAdaptor.create(this);
            return socket;
        }
    }

    @Override
    public SocketAddress getLocalAddress() throws IOException {
        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            return localAddress == null ? localAddress
                    : Net.getRevealedLocalAddress(
                          Net.asInetSocketAddress(localAddress));
        }
    }

    @Override
    public <T> ServerSocketChannel setOption(SocketOption<T> name, T value)
        throws IOException
    {
        if (name == null)
            throw new NullPointerException();
        if (!supportedOptions().contains(name))
            throw new UnsupportedOperationException("'" + name + "' not supported");
        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            if (name == StandardSocketOptions.SO_REUSEADDR &&
                    Net.useExclusiveBind())
            {
                // SO_REUSEADDR emulated when using exclusive bind
                isReuseAddress = (Boolean)value;
            } else {
                // no options that require special handling
                Net.setSocketOption(fd, Net.UNSPEC, name, value);
            }
            return this;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getOption(SocketOption<T> name)
        throws IOException
    {
        if (name == null)
            throw new NullPointerException();
        if (!supportedOptions().contains(name))
            throw new UnsupportedOperationException("'" + name + "' not supported");

        synchronized (stateLock) {
            if (!isOpen())
                throw new ClosedChannelException();
            if (name == StandardSocketOptions.SO_REUSEADDR &&
                    Net.useExclusiveBind())
            {
                // SO_REUSEADDR emulated when using exclusive bind
                return (T)Boolean.valueOf(isReuseAddress);
            }
            // no options that require special handling
            return (T) Net.getSocketOption(fd, Net.UNSPEC, name);
        }
    }

    private static class DefaultOptionsHolder {
        static final Set<SocketOption<?>> defaultOptions = defaultOptions();

        private static Set<SocketOption<?>> defaultOptions() {
            HashSet<SocketOption<?>> set = new HashSet<SocketOption<?>>(2);
            set.add(StandardSocketOptions.SO_RCVBUF);
            set.add(StandardSocketOptions.SO_REUSEADDR);
            return Collections.unmodifiableSet(set);
        }
    }

    @Override
    public final Set<SocketOption<?>> supportedOptions() {
        return DefaultOptionsHolder.defaultOptions;
    }

    public boolean isBound() {
        synchronized (stateLock) {
            return localAddress != null;
        }
    }

    public InetSocketAddress localAddress() {
        synchronized (stateLock) {
            return localAddress;
        }
    }

    @Override
    public ServerSocketChannel bind(SocketAddress local, int backlog) throws IOException {
        synchronized (lock) {
            // 如果channel不是open的，报错
            if (!isOpen())
                throw new ClosedChannelException();
            // 如果channel已经绑定了一个地址了，抛出异常
            if (isBound())
                throw new AlreadyBoundException();
            // 如果传入的local为null，创建一个InetSocketAddress，指向本机地址，且端口为0；
            // 否则对local地址进行检查
            InetSocketAddress isa = (local == null) ? new InetSocketAddress(0) :
                Net.checkAddress(local);
            SecurityManager sm = System.getSecurityManager();
            if (sm != null)
                sm.checkListen(isa.getPort());
            // 调用tcp绑定之前的钩子函数
            NetHooks.beforeTcpBind(fd, isa.getAddress(), isa.getPort());
            // 调用Net的bind方法将文件描述符 和 socket地址绑定起来
            Net.bind(fd, isa.getAddress(), isa.getPort());
            // 监听文件描述符
            Net.listen(fd, backlog < 1 ? 50 : backlog);
            synchronized (stateLock) {
                // 根据fd获取本机的socket地址给serverChannel的localAddress持有
                localAddress = Net.localAddress(fd);
            }
        }
        // 返回自身
        return this;
    }

    public SocketChannel accept() throws IOException {
        synchronized (lock) {
            if (!isOpen())
                throw new ClosedChannelException();
            if (!isBound())
                throw new NotYetBoundException();
            SocketChannel sc = null;

            int n = 0;
            FileDescriptor newfd = new FileDescriptor();
            InetSocketAddress[] isaa = new InetSocketAddress[1];

            try {
                begin();
                if (!isOpen())
                    return null;
                thread = NativeThread.current();
                for (;;) {
                    n = accept0(this.fd, newfd, isaa);
                    if ((n == IOStatus.INTERRUPTED) && isOpen())
                        continue;
                    break;
                }
            } finally {
                thread = 0;
                end(n > 0);
                assert IOStatus.check(n);
            }

            if (n < 1)
                return null;

            IOUtil.configureBlocking(newfd, true);
            InetSocketAddress isa = isaa[0];
            sc = new SocketChannelImpl(provider(), newfd, isa);
            SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                try {
                    sm.checkAccept(isa.getAddress().getHostAddress(),
                                   isa.getPort());
                } catch (SecurityException x) {
                    sc.close();
                    throw x;
                }
            }
            return sc;

        }
    }

    protected void implConfigureBlocking(boolean block) throws IOException {
        IOUtil.configureBlocking(fd, block);
    }

    protected void implCloseSelectableChannel() throws IOException {
        synchronized (stateLock) {
            if (state != ST_KILLED)
                nd.preClose(fd);
            long th = thread;
            if (th != 0)
                NativeThread.signal(th);
            if (!isRegistered())
                kill();
        }
    }

    public void kill() throws IOException {
        synchronized (stateLock) {
            if (state == ST_KILLED)
                return;
            if (state == ST_UNINITIALIZED) {
                state = ST_KILLED;
                return;
            }
            assert !isOpen() && !isRegistered();
            nd.close(fd);
            state = ST_KILLED;
        }
    }

    /**
     * Translates native poll revent set into a ready operation set
     */
    public boolean translateReadyOps(int ops, int initialOps,
                                     SelectionKeyImpl sk) {
        int intOps = sk.nioInterestOps(); // Do this just once, it synchronizes
        int oldOps = sk.nioReadyOps();
        int newOps = initialOps;

        // 如果poll event是POLLNVAL，返回false
        if ((ops & PollArrayWrapper.POLLNVAL) != 0) {
            // This should only happen if this channel is pre-closed while a
            // selection operation is in progress
            // ## Throw an error if this channel has not been pre-closed
            return false;
        }

        // 如果poll event里面存在POLLERR 或者 POLLHUP
        if ((ops & (PollArrayWrapper.POLLERR
                    | PollArrayWrapper.POLLHUP)) != 0) {
            // 设置sk的nioReadOps为nioInterestOps
            newOps = intOps;
            sk.nioReadyOps(newOps);
            // 将newOps和oldOps取反进行与操作，返回结果是否等于0
            // 表示更新是否成功
            return (newOps & ~oldOps) != 0;
        }

        // 如果poll event里面存在POLLIN 并且 sk中感兴趣的nio事件有ACCEPT
        if (((ops & PollArrayWrapper.POLLIN) != 0) &&
            ((intOps & SelectionKey.OP_ACCEPT) != 0))
            // 将newOps中添加nio的ACCEPT事件
                newOps |= SelectionKey.OP_ACCEPT;

        // 设置sk的nioReadyOps
        sk.nioReadyOps(newOps);
        // 将新的ops和原本的ops取反进行比较，如果不等于0，说明更新成功，返回true
        return (newOps & ~oldOps) != 0;
    }

    public boolean translateAndUpdateReadyOps(int ops, SelectionKeyImpl sk) {
        return translateReadyOps(ops, sk.nioReadyOps(), sk);
    }

    public boolean translateAndSetReadyOps(int ops, SelectionKeyImpl sk) {
        // 翻译epoll事件为nio事件，设置到sk的readyOps属性中，并且初始的newOps设置为0
        return translateReadyOps(ops, 0, sk);
    }

    // package-private
    int poll(int events, long timeout) throws IOException {
        assert Thread.holdsLock(blockingLock()) && !isBlocking();

        synchronized (lock) {
            int n = 0;
            try {
                begin();
                synchronized (stateLock) {
                    if (!isOpen())
                        return 0;
                    thread = NativeThread.current();
                }
                n = Net.poll(fd, events, timeout);
            } finally {
                thread = 0;
                end(n > 0);
            }
            return n;
        }
    }

    /**
     * Translates an interest operation set into a native poll event set
     */
    // 将感兴趣的操作翻译哼本地的poll事件
    public void translateAndSetInterestOps(int ops, SelectionKeyImpl sk) {
        int newOps = 0;

        // Translate ops
        // 如果ops是ACCEPT的话，将其转换成epoll的POLLIN事件
        if ((ops & SelectionKey.OP_ACCEPT) != 0)
            newOps |= PollArrayWrapper.POLLIN;
        // Place ops into pollfd array
        // 将事件设置到selector持有的pollArray中
        sk.selector.putEventOps(sk, newOps);
    }

    public FileDescriptor getFD() {
        return fd;
    }

    public int getFDVal() {
        return fdVal;
    }

    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append(this.getClass().getName());
        sb.append('[');
        if (!isOpen()) {
            sb.append("closed");
        } else {
            synchronized (stateLock) {
                InetSocketAddress addr = localAddress();
                if (addr == null) {
                    sb.append("unbound");
                } else {
                    sb.append(Net.getRevealedLocalAddressAsString(addr));
                }
            }
        }
        sb.append(']');
        return sb.toString();
    }

    // -- Native methods --

    // Accepts a new connection, setting the given file descriptor to refer to
    // the new socket and setting isaa[0] to the socket's remote address.
    // Returns 1 on success, or IOStatus.UNAVAILABLE (if non-blocking and no
    // connections are pending) or IOStatus.INTERRUPTED.
    //
    private native int accept0(FileDescriptor ssfd, FileDescriptor newfd,
                               InetSocketAddress[] isaa)
        throws IOException;

    private static native void initIDs();

    static {
        IOUtil.load();
        initIDs();
        nd = new SocketDispatcher();
    }

}
