/*****************************************************************************
 * Copyright 2011-2012 INRIA
 * Copyright 2011-2012 Universidade Nova de Lisboa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *****************************************************************************/
package sys.net.api.rpc;

import sys.net.api.Endpoint;

public interface RpcHandle {

    /**
     * Obtain the message payload that produced this handle
     * 
     * @return the message payload associated with this handle
     */
    RpcMessage getPayload();

    /**
     * Telss if this handle was generated by a send operation that supplied a
     * reply handler
     * 
     * @return true/false if the message associated with this handle expects a
     *         reply.
     */
    boolean expectingReply();

    /**
     * Send a (final) reply message using this connection.
     * 
     * @param msg
     *            the reply being sent
     * @return the handle associated for the message
     */
    RpcHandle reply(final RpcMessage msg);

    /**
     * Send a reply message using this connection, with a further message
     * exchange round implied. Blocks the calling thread until the reply is
     * received, and the calling thread will invoke the handler.
     * 
     * @param msg
     *            the reply message
     * @param handler
     *            the handler that will be notified upon the arrival of an reply
     *            (to this reply)
     * @return the handle associated for the message
     */
    RpcHandle reply(final RpcMessage msg, final RpcHandler handler);

    /**
     * Send a reply message using this connection, with a further message
     * exchange round implied. If the timeout is greater than 0, blocks the
     * calling thread until the reply is received, and the calling thread will
     * invoke the handler. If the timeout is 0, returns without waiting for the
     * reply, and the reply handler will be invoked in another thread.
     * 
     * @param msg
     *            the reply message
     * @param handler
     *            the handler that will be notified upon the arrival of an reply
     *            (to this reply)
     * 
     * @param timeout
     *            number of milliseconds to block waiting for a reply. 0 - means
     *            no blocking/asynchronous. The handled will be called by a
     *            different thread.
     * @return the handle associated for the message
     */
    RpcHandle reply(final RpcMessage msg, final RpcHandler handler, int timeout);

    /**
     * Tells if the send operation failed.
     * 
     * @return true/false whether the send operation was successful or not.
     */
    boolean failed();

    /**
     * Tells if the send operation failed.
     * 
     * @return true/false whether the send operation was successful or not.
     */
    boolean succeeded();

    /**
     * Obtains the remote endpoint of this connection
     * 
     * @return the remote endpoint of this connection
     */
    Endpoint remoteEndpoint();

    /**
     * In blocking mode, this method returns the handle to the reply message.
     * 
     * @return The handle associated with the reply to the message sent that
     *         returned this handle
     */
    RpcHandle getReply();
}
