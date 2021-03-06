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
package sys.net.impl;

import static sys.net.api.Networking.Networking;
import sys.Sys;
import sys.net.api.Networking.TransportProvider;
import sys.net.api.rpc.RpcEndpoint;
import sys.net.api.rpc.RpcHandle;

public class RpcServer extends Handler {

    final static int PORT = 9999;

    RpcEndpoint endpoint;

    RpcServer() {
        endpoint = Networking.rpcBind(PORT, TransportProvider.INPROC).toService(0, this);

        System.out.println("Server ready...");
    }

    @Override
    public void onReceive(final RpcHandle handle, final Request req) {
        handle.reply(new Reply(req.val));
    }

    /*
     * The server class...
     */
    public static void main(final String[] args) {

        Sys.init();

        KryoLib.register(Request.class, 0x100);
        KryoLib.register(Reply.class, 0x101);

        new RpcServer();
    }
}
