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

import sys.net.api.Endpoint;
import sys.net.api.Message;
import sys.net.impl.providers.AbstractTransport;

public class FailedTransportConnection extends AbstractTransport {

	Throwable cause;

	public FailedTransportConnection(Endpoint local, Endpoint remote, Throwable cause) {
		super(local, remote);
		this.isBroken = true;
		this.cause = cause;
	}

	@Override
	public boolean send(Message m) {
		return false;
	}

	@Override
	public <T extends Message> T receive() {
		return null;
	}

	public Throwable causeOfFailure() {
		return cause;
	}

	@Override
	public boolean sendNow(Message m) {
		return false;
	}
	
	public String toString() {
		return "Broken";
	}
}