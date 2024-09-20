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

import java.io.IOException;
import java.nio.channels.*;
import java.nio.channels.spi.*;
import java.net.SocketException;
import java.util.*;


/**
 * Base Selector implementation class.
 */

public abstract class SelectorImpl
    extends AbstractSelector
{

    // The set of keys with data ready for an operation
    protected Set<SelectionKey> selectedKeys;

    // The set of keys registered with this Selector
    protected HashSet<SelectionKey> keys;

    // Public views of the key sets
    private Set<SelectionKey> publicKeys;             // Immutable
    private Set<SelectionKey> publicSelectedKeys;     // Removal allowed, but not addition

    protected SelectorImpl(SelectorProvider sp) {
        // 将selectorProvider给父类持有
        super(sp);
        // 初始化keys和selectedKeys这两个集合
        keys = new HashSet<SelectionKey>();
        selectedKeys = new HashSet<SelectionKey>();
        // 如果bugLevel等于1.4，将public相关的集合引用直接指向keys和selectedKeys
        if (Util.atBugLevel("1.4")) {
            publicKeys = keys;
            publicSelectedKeys = selectedKeys;
        } else {
            // 否则，使用Collections进行包装，让其变为不可变的集合
            publicKeys = Collections.unmodifiableSet(keys);
            publicSelectedKeys = Util.ungrowableSet(selectedKeys);
        }
    }

    public Set<SelectionKey> keys() {
        if (!isOpen() && !Util.atBugLevel("1.4"))
            throw new ClosedSelectorException();
        return publicKeys;
    }

    public Set<SelectionKey> selectedKeys() {
        if (!isOpen() && !Util.atBugLevel("1.4"))
            throw new ClosedSelectorException();
        return publicSelectedKeys;
    }

    protected abstract int doSelect(long timeout) throws IOException;

    private int lockAndDoSelect(long timeout) throws IOException {
        // 加锁
        synchronized (this) {
            // 如果selector不是open的话，报错
            if (!isOpen())
                throw new ClosedSelectorException();
            // 对publicKeys和publicSelectedKeys加锁
            synchronized (publicKeys) {
                synchronized (publicSelectedKeys) {
                    // 调用doSelect方法执行具体的select逻辑
                    return doSelect(timeout);
                }
            }
        }
    }

    public int select(long timeout)
        throws IOException
    {
        if (timeout < 0)
            throw new IllegalArgumentException("Negative timeout");
        return lockAndDoSelect((timeout == 0) ? -1 : timeout);
    }

    public int select() throws IOException {
        return select(0);
    }

    public int selectNow() throws IOException {
        return lockAndDoSelect(0);
    }

    public void implCloseSelector() throws IOException {
        wakeup();
        synchronized (this) {
            synchronized (publicKeys) {
                synchronized (publicSelectedKeys) {
                    implClose();
                }
            }
        }
    }

    protected abstract void implClose() throws IOException;

    public void putEventOps(SelectionKeyImpl sk, int ops) { }

    protected final SelectionKey register(AbstractSelectableChannel ch,
                                          int ops,
                                          Object attachment)
    {
        // 如果传入的channel不是SelChImpl类型的，报错
        if (!(ch instanceof SelChImpl))
            throw new IllegalSelectorException();
        // 创建一个SelectionKeyImpl对象，将channel和自身这个selector都传入
        SelectionKeyImpl k = new SelectionKeyImpl((SelChImpl)ch, this);
        // 通过cas替换selectionKey持有的attachment变量
        k.attach(attachment);
        // 对publicKeys加锁
        synchronized (publicKeys) {
            // 调用具体的注册逻辑
            implRegister(k);
        }
        // 设置channel感兴趣的operation到selectionKey中
        // 并且会将ops通过channel翻译成对应的poll事件，调用selector的putEventOps方法
        // 将翻译后的ops存入到pollWrapper中
        k.interestOps(ops);
        return k;
    }

    protected abstract void implRegister(SelectionKeyImpl ski);

    void processDeregisterQueue() throws IOException {
        // Precondition: Synchronized on this, keys, and selectedKeys
        // 获取selector中已经取消的SelectionKey集合
        Set<SelectionKey> cks = cancelledKeys();
        // 加锁遍历
        synchronized (cks) {
            if (!cks.isEmpty()) {
                Iterator<SelectionKey> i = cks.iterator();
                while (i.hasNext()) {
                    SelectionKeyImpl ski = (SelectionKeyImpl)i.next();
                    try {
                        // 调用implDereg进行注销
                        implDereg(ski);
                    } catch (SocketException se) {
                        throw new IOException("Error deregistering key", se);
                    } finally {
                        // 然后从集合中删除
                        i.remove();
                    }
                }
            }
        }
    }

    protected abstract void implDereg(SelectionKeyImpl ski) throws IOException;

    abstract public Selector wakeup();

}
