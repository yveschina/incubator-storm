/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package backtype.storm.nimbus;

import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.utils.ZKPaths;

import backtype.storm.Config;
import backtype.storm.utils.Utils;

@SuppressWarnings("rawtypes")
public class NimbusLeadership {

	private static final String STORM_NIMBUS_LEADERSHIP_PATH = "/nimbus/leadership";

	private Map conf;
	private CuratorFramework curator;
	private InterProcessMutex mutex;
	private boolean isLeader = false;

	public NimbusLeadership(final Map conf) {
		this.conf = conf;
	}

	public void acquireLeaderShip() throws Exception {
		String nimbusHostName = InetAddress.getLocalHost().getCanonicalHostName();
		Object nimbusPort = conf.get(Config.NIMBUS_THRIFT_PORT);
		String nodeId = nimbusHostName + ":" + nimbusPort.toString();
		initCurator();
		initLeadershipMutex(nodeId);
		mutex.acquire();
		isLeader = true;
	}

	public InetSocketAddress getNimbusLeaderAddress() throws Exception {
		InetSocketAddress leaderAddress = null;
		initCurator();
		initLeadershipMutex(null);
		Collection<String> nimbusNodesPath = mutex.getParticipantNodes();
		if (nimbusNodesPath.size() > 0) {
			leaderAddress = parseAddress(nimbusNodesPath.iterator().next());
		}
		close();
		return leaderAddress;
	}

	public List<InetSocketAddress> getNimbusHosts() throws Exception {
		List<InetSocketAddress> nimbusAddressList = new ArrayList<InetSocketAddress>();
		initCurator();
		initLeadershipMutex(null);
		Collection<String> nimbusNodesPath = mutex.getParticipantNodes();
		for (String nimbusNodePath : nimbusNodesPath) {
			nimbusAddressList.add(parseAddress(nimbusNodePath));
		}
		close();
		return nimbusAddressList;
	}

	public void close() {
		if (isLeader) {
			try {
				mutex.release();
			} catch (Exception e) {
				throw new RuntimeException("Exception while releasing mutex", e);
			}
		}
		curator.close();
	}

	@SuppressWarnings("unchecked")
	private void initCurator() throws Exception {
		List<String> servers = (List<String>) conf.get(Config.STORM_ZOOKEEPER_SERVERS);
		Object port = conf.get(Config.STORM_ZOOKEEPER_PORT);
		this.curator = Utils.newCuratorStarted(conf, servers, port);
	}

	private void initLeadershipMutex(final String nodeId) throws Exception {
		String path = (String) conf.get(Config.STORM_ZOOKEEPER_ROOT) + STORM_NIMBUS_LEADERSHIP_PATH;
		ZKPaths.mkdirs(curator.getZookeeperClient().getZooKeeper(), path);
		mutex = new InterProcessMutex(curator, path) {
			@Override
			protected byte[] getLockNodeBytes() {
				try {
					return nodeId == null ? null : nodeId.getBytes("UTF-8");
				} catch (UnsupportedEncodingException e) {
					throw new RuntimeException("UTF-8 isn't supported", e);
				}
			}
		};
	}

	private InetSocketAddress parseAddress(String nimbusNodePath) throws Exception {
		String nimbusNodeData = new String(curator.getData().forPath(nimbusNodePath), "UTF-8");
		String[] split = nimbusNodeData.split(":");
		return new InetSocketAddress(split[0], Integer.parseInt(split[1]));
	}
}
