/*
 * Copyright (c) 2005, 2012, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.security.AccessController;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import sun.security.action.GetIntegerAction;

/**
 * Manipulates a native array of epoll_event structs on Linux:
 *
 * typedef union epoll_data {
 *     void *ptr;
 *     int fd;
 *     __uint32_t u32;
 *     __uint64_t u64;
 *  } epoll_data_t;
 *
 * struct epoll_event {
 *     __uint32_t events;
 *     epoll_data_t data;
 * };
 *
 * The system call to wait for I/O events is epoll_wait(2). It populates an
 * array of epoll_event structures that are passed to the call. The data
 * member of the epoll_event structure contains the same data as was set
 * when the file descriptor was registered to epoll via epoll_ctl(2). In
 * this implementation we set data.fd to be the file descriptor that we
 * register. That way, we have the file descriptor available when we
 * process the events.
 */

class EPollArrayWrapper {
    // EPOLL_EVENTS
    private static final int EPOLLIN      = 0x001;

    // opcodes
    private static final int EPOLL_CTL_ADD      = 1;
    private static final int EPOLL_CTL_DEL      = 2;
    private static final int EPOLL_CTL_MOD      = 3;

    // Miscellaneous constants
    // 每个epoll_event的size
    private static final int SIZE_EPOLLEVENT  = sizeofEPollEvent();
    private static final int EVENT_OFFSET     = 0;
    private static final int DATA_OFFSET      = offsetofData();
    private static final int FD_OFFSET        = DATA_OFFSET;
    // 能够打开的文件描述符的最大数量
    private static final int OPEN_MAX         = IOUtil.fdLimit();
    // 能够监听的epoll_event的最大数量，是能够打开的fd数量和8192的更小值
    private static final int NUM_EPOLLEVENTS  = Math.min(OPEN_MAX, 8192);

    // Special value to indicate that an update should be ignored
    // 一个特殊值，表示对应fd的events更新应该被忽略
    private static final byte  KILLED = (byte)-1;

    // Initial size of arrays for fd registration changes
    private static final int INITIAL_PENDING_UPDATE_SIZE = 64;

    // maximum size of updatesLow
    private static final int MAX_UPDATE_ARRAY_SIZE = AccessController.doPrivileged(
        new GetIntegerAction("sun.nio.ch.maxUpdateArraySize", Math.min(OPEN_MAX, 64*1024)));

    // The fd of the epoll driver
    // epoll驱动对应的文件描述符
    private final int epfd;

     // The epoll_event array for results from epoll_wait
    // 维护了所有epoll_event的数组，都是epoll_wait的结果
    private final AllocatedNativeObject pollArray;

    // Base address of the epoll_event array
    private final long pollArrayAddress;

    // The fd of the interrupt line going out
    private int outgoingInterruptFD;

    // The fd of the interrupt line coming in
    private int incomingInterruptFD;

    // The index of the interrupt FD
    private int interruptedIndex;

    // Number of updated pollfd entries
    int updated;

    // object to synchronize fd registration changes
    // 用于对文件描述符注册的变化进行加锁
    private final Object updateLock = new Object();

    // number of file descriptors with registration changes pending
    private int updateCount;

    // file descriptors with registration changes pending
    private int[] updateDescriptors = new int[INITIAL_PENDING_UPDATE_SIZE];

    // events for file descriptors with registration changes pending, indexed
    // by file descriptor and stored as bytes for efficiency reasons. For
    // file descriptors higher than MAX_UPDATE_ARRAY_SIZE (unlimited case at
    // least) then the update is stored in a map.
    // 用于保存和索引文件描述符的events的，如果文件描述符大于了MAX_UPDATE_ARRAY_SIZE，那么会保存在一个map中
    private final byte[] eventsLow = new byte[MAX_UPDATE_ARRAY_SIZE];
    private Map<Integer,Byte> eventsHigh;

    // Used by release and updateRegistrations to track whether a file
    // descriptor is registered with epoll.
    // 用于判断某个文件描述符是否已经注册到EpollArrayWrapper中了
    private final BitSet registered = new BitSet();


    EPollArrayWrapper() throws IOException {
        // creates the epoll file descriptor
        // 创建epoll的文件描述符
        epfd = epollCreate();

        // the epoll_event array passed to epoll_wait
        // 计算epoll需要的数组长度
        int allocationSize = NUM_EPOLLEVENTS * SIZE_EPOLLEVENT;
        // 通过unsafe分配一个本地对象作为pollArray
        pollArray = new AllocatedNativeObject(allocationSize, true);
        // 返回pollArray数组内容开始的内存地址，由于对齐的原因，分配的实际地址可能不等于内容开始地址
        pollArrayAddress = pollArray.address();

        // eventHigh needed when using file descriptors > 64k
        // 如果能够开启的fd的最大数量大于了 MAX_UPDATE_ARRAY_SIZE，初始化eventsHigh这个map用于保存大于MAX_UPDATE_ARRAY_SIZE的fd以及对应的events
        if (OPEN_MAX > MAX_UPDATE_ARRAY_SIZE)
            eventsHigh = new HashMap<>();
    }

    void initInterrupt(int fd0, int fd1) {
        outgoingInterruptFD = fd1;
        incomingInterruptFD = fd0;
        // 将读interrupt状态的fd添加进epoll实例中，并且对EPOLLIN事件进行监听
        epollCtl(epfd, EPOLL_CTL_ADD, fd0, EPOLLIN);
    }

    void putEventOps(int i, int event) {
        int offset = SIZE_EPOLLEVENT * i + EVENT_OFFSET;
        pollArray.putInt(offset, event);
    }

    void putDescriptor(int i, int fd) {
        int offset = SIZE_EPOLLEVENT * i + FD_OFFSET;
        pollArray.putInt(offset, fd);
    }

    int getEventOps(int i) {
        int offset = SIZE_EPOLLEVENT * i + EVENT_OFFSET;
        return pollArray.getInt(offset);
    }

    int getDescriptor(int i) {
        int offset = SIZE_EPOLLEVENT * i + FD_OFFSET;
        return pollArray.getInt(offset);
    }

    /**
     * Returns {@code true} if updates for the given key (file
     * descriptor) are killed.
     */
    private boolean isEventsHighKilled(Integer key) {
        assert key >= MAX_UPDATE_ARRAY_SIZE;
        Byte value = eventsHigh.get(key);
        return (value != null && value == KILLED);
    }

    /**
     * Sets the pending update events for the given file descriptor. This
     * method has no effect if the update events is already set to KILLED,
     * unless {@code force} is {@code true}.
     * 为给出的文件描述符设置延迟更新的事件。如果这个更新的事件已经被设置为KILLED，那么这个方法不会起作用，除非force参数为true
     */
    private void setUpdateEvents(int fd, byte events, boolean force) {
        // 如果文件描述符小于 MAX_UPDATE_ARRAY_SIZE
        if (fd < MAX_UPDATE_ARRAY_SIZE) {
            // 判断文件描述符对应的events是否不是KILLEd 或者 force为true
            if ((eventsLow[fd] != KILLED) || force) {
                // 将events填入eventsLow数组中，表示对应文件描述符的events
                eventsLow[fd] = events;
            }
        }
        // 如果fd大于等于了 MAX_UPDATE_ARRAY_SIZE，使用eventsHigh来维护fd对应的events
        else {
            Integer key = Integer.valueOf(fd);
            if (!isEventsHighKilled(key) || force) {
                eventsHigh.put(key, Byte.valueOf(events));
            }
        }
    }

    /**
     * Returns the pending update events for the given file descriptor.
     */
    private byte getUpdateEvents(int fd) {
        if (fd < MAX_UPDATE_ARRAY_SIZE) {
            return eventsLow[fd];
        } else {
            Byte result = eventsHigh.get(Integer.valueOf(fd));
            // result should never be null
            return result.byteValue();
        }
    }

    /**
     * Update the events for a given file descriptor
     * 更新一个给出的fd的events
     */
    void setInterest(int fd, int mask) {
        // 加锁
        synchronized (updateLock) {
            // record the file descriptor and events
            // 记录更新的fd和对应的events
            // 判断记录的数组长度是否足够，如果不够，进行扩容
            int oldCapacity = updateDescriptors.length;
            if (updateCount == oldCapacity) {
                int newCapacity = oldCapacity + INITIAL_PENDING_UPDATE_SIZE;
                int[] newDescriptors = new int[newCapacity];
                System.arraycopy(updateDescriptors, 0, newDescriptors, 0, oldCapacity);
                updateDescriptors = newDescriptors;
            }
            // 然后将fd添加到数组的末尾，将updateCount + 1
            updateDescriptors[updateCount++] = fd;

            // events are stored as bytes for efficiency reasons
            // events由于效率原因被保存为byte
            byte b = (byte)mask;
            assert (b == mask) && (b != KILLED);
            setUpdateEvents(fd, b, false);
        }
    }

    /**
     * Add a file descriptor
     */
    void add(int fd) {
        // force the initial update events to 0 as it may be KILLED by a
        // previous registration.
        // 加锁
        synchronized (updateLock) {
            // 确认文件描述符是没有注册的
            assert !registered.get(fd);
            // 调用setUpdateEvents，向pollArray中注册文件描述符
            // 但监听的事件为0，表示没有
            setUpdateEvents(fd, (byte)0, true);
        }
    }

    /**
     * Remove a file descriptor
     */
    void remove(int fd) {
        synchronized (updateLock) {
            // kill pending and future update for this file descriptor
            // 将eventsLow或者eventsHigh里面fd对应的事件设置为KILLED
            setUpdateEvents(fd, KILLED, false);

            // remove from epoll
            // 如果fd已经注册进epoll实例了
            if (registered.get(fd)) {
                // 调用epollCtl方法，将fd从epoll实例中删除
                epollCtl(epfd, EPOLL_CTL_DEL, fd, 0);
                // 并且将registered这个BitSet里面fd对应的二进制置为0
                registered.clear(fd);
            }
        }
    }

    /**
     * Close epoll file descriptor and free poll array
     */
    void closeEPollFD() throws IOException {
        FileDispatcherImpl.closeIntFD(epfd);
        pollArray.free();
    }

    int poll(long timeout) throws IOException {
        // 更新从上一次select结束到本次select开始之间的新注册的fd，
        // 即解析updateRegistrations数组和updateCount，更新完成后将updateCount置为0，重置数组。
        // 调用epollCtl方法将fd和events添加进epoll实例中，进行维护
        updateRegistrations();
        // 调用epollWait方法，监听epoll实例里面维护的fd对应的events是否触发，
        // 如果触发，会将fd和events封装成epoll_event结构体添加到pollArrayAddress这个地址开始的字节数组中。
        // 该方法会返回有多少个事件被监听到了且维护在pollArray中了
        updated = epollWait(pollArrayAddress, NUM_EPOLLEVENTS, timeout, epfd);
        // 遍历pollArray数组
        for (int i=0; i<updated; i++) {
            // 获取每个epoll_event对应的fd，如果发现它等于incomingInterruptFD的话
            if (getDescriptor(i) == incomingInterruptFD) {
                // 将interruptedIndex设置为i，并且将interrupted标志置为true
                interruptedIndex = i;
                interrupted = true;
                // 跳出循环
                break;
            }
        }
        // 返回监听到的epoll_event数量
        return updated;
    }

    /**
     * Update the pending registrations.
     */
    private void updateRegistrations() {
        synchronized (updateLock) {
            int j = 0;
            while (j < updateCount) {
                // 遍历存入到当前EpollArrayWrapper里面的fd
                int fd = updateDescriptors[j];
                // 根据eventsLow或者eventsHigh获取fd对应的events
                short events = getUpdateEvents(fd);
                // 根据registered这个BitSet判断当前的fd是否已经注册
                boolean isRegistered = registered.get(fd);
                int opcode = 0;

                // 如果events不等于KILLED的话
                if (events != KILLED) {
                    // 如果fd已经注册了，根据events判断opCode的值。
                    // 如果events等于0，说明该事件已经被删除了 EPOLL_CTL_DEL；
                    // 如果events不等于0，说明该事件修改了 EPOLL_CTL_MOD
                    if (isRegistered) {
                        opcode = (events != 0) ? EPOLL_CTL_MOD : EPOLL_CTL_DEL;
                    } else {
                        // 如果fd还没有注册，且events不等于0，opcode为EPOLL_CTL_ADD，表示新增注册
                        opcode = (events != 0) ? EPOLL_CTL_ADD : 0;
                    }
                    // 如果opcode不等于0
                    if (opcode != 0) {
                        // 调用epollCtl方法，将epfd、opcode、fd、events都传入
                        // epollCtl方法是向epoll实例中添加、修改和删除对应fd感兴趣的事件，
                        // 在注册的时候就需要告诉epoll实例该fd感兴趣的事件了
                        epollCtl(epfd, opcode, fd, events);
                        // 如果opcode等于EPOLL_CTL_ADD
                        if (opcode == EPOLL_CTL_ADD) {
                            // 将Bitset中fd对应的二进制设置为1，表示该fd已经注册
                            registered.set(fd);
                        }
                        // 如果opcode等于EPOLL_CTL_DEL
                        else if (opcode == EPOLL_CTL_DEL) {
                            // 将BitSet中fd对应的二进制设置为0，表示取消注册
                            registered.clear(fd);
                        }
                    }
                }
                // 继续遍历
                j++;
            }
            // 然后将updateCount置为0，表示已经处理完上一次select到本次select之间的更新操作
            updateCount = 0;
        }
    }

    // interrupt support
    private boolean interrupted = false;

    public void interrupt() {
        // 向outgoingInterruptFD这个写fd写入interrupt信息
        interrupt(outgoingInterruptFD);
    }

    public int interruptedIndex() {
        return interruptedIndex;
    }

    boolean interrupted() {
        return interrupted;
    }

    void clearInterrupted() {
        interrupted = false;
    }

    static {
        IOUtil.load();
        init();
    }

    private native int epollCreate();
    private native void epollCtl(int epfd, int opcode, int fd, int events);
    private native int epollWait(long pollAddress, int numfds, long timeout,
                                 int epfd) throws IOException;
    private static native int sizeofEPollEvent();
    private static native int offsetofData();
    private static native void interrupt(int fd);
    private static native void init();
}
