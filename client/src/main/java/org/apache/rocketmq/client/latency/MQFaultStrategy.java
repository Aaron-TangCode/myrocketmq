/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.client.latency;

import org.apache.rocketmq.client.impl.producer.TopicPublishInfo;
import org.apache.rocketmq.client.log.ClientLogger;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.common.message.MessageQueue;

public class MQFaultStrategy {
    private final static InternalLogger log = ClientLogger.getLog();
    private final LatencyFaultTolerance<String> latencyFaultTolerance = new LatencyFaultToleranceImpl();

    private boolean sendLatencyFaultEnable = false;

    //延迟毫秒
    private long[] latencyMax = {50L, 100L, 550L, 1000L, 2000L, 3000L, 15000L};
    //延迟毫秒大于550毫秒，代表不可用时长为30秒
    private long[] notAvailableDuration = {0L, 0L, 30000L, 60000L, 120000L, 180000L, 600000L};

    public long[] getNotAvailableDuration() {
        return notAvailableDuration;
    }

    public void setNotAvailableDuration(final long[] notAvailableDuration) {
        this.notAvailableDuration = notAvailableDuration;
    }

    public long[] getLatencyMax() {
        return latencyMax;
    }

    public void setLatencyMax(final long[] latencyMax) {
        this.latencyMax = latencyMax;
    }

    public boolean isSendLatencyFaultEnable() {
        return sendLatencyFaultEnable;
    }

    public void setSendLatencyFaultEnable(final boolean sendLatencyFaultEnable) {
        this.sendLatencyFaultEnable = sendLatencyFaultEnable;
    }

    /**
     * 为保证发送消息的高可用，引入了2个特性：
     * 1、消息重试机制
     * 2、故障规避机制(默认开启)
     * sendLatencyFaultEnable 默认为false,但默认故障规避机制是开启的，但如果sendLatencyFaultEnable为false,在重试发送消息时，才会启动故障规避机制
     *
     * 故障规避机制：
     * 第一次发送消息时失败，第二次发送时，如果发送消息给刚刚失败的broker，很大概率是会发送失败的，为了提高发送消息的成功率。
     * 第二次发送时，会选择其他broker进行发送消息，这样子，可以提高发送消息的成功率。
     *
     * 如果没开启故障规避机制，即使有消息重试机制，也有可能会选择同一个Broker
     *
     * @param tpInfo
     * @param lastBrokerName
     * @return
     */
    public MessageQueue selectOneMessageQueue(final TopicPublishInfo tpInfo, final String lastBrokerName) {
        // 为了发送消息的高可用，引入的延时规避机制，这个是重试的时候，才会触发的
        if (this.sendLatencyFaultEnable) {
            try {
                //轮询，负载均衡。比如有3个queue,第一次发送到queue1,第二次发送到queue2
                int index = tpInfo.getSendWhichQueue().getAndIncrement();
                for (int i = 0; i < tpInfo.getMessageQueueList().size(); i++) {
                    int pos = Math.abs(index++) % tpInfo.getMessageQueueList().size();
                    if (pos < 0)
                        pos = 0;
                    //选队列
                    MessageQueue mq = tpInfo.getMessageQueueList().get(pos);
                    // 看找到的这个queue所属的broker是不是可用的。可用的话，就返回queue，不可用就跳过，继续找下一个
                    if (latencyFaultTolerance.isAvailable(mq.getBrokerName()))
                        return mq;
                }
                // 如果所有队列都不可用，那么选择一个相对好的broker，不考虑可用性的消息队列
                final String notBestBroker = latencyFaultTolerance.pickOneAtLeast();
                //根据broker获取队列数
                int writeQueueNums = tpInfo.getQueueIdByBroker(notBestBroker);
                if (writeQueueNums > 0) {
                    final MessageQueue mq = tpInfo.selectOneMessageQueue();
                    if (notBestBroker != null) {
                        mq.setBrokerName(notBestBroker);
                        mq.setQueueId(tpInfo.getSendWhichQueue().getAndIncrement() % writeQueueNums);
                    }
                    return mq;
                } else {
                    latencyFaultTolerance.remove(notBestBroker);
                }
            } catch (Exception e) {
                log.error("Error occurred when selecting message queue", e);
            }

            // 随机选择一个queue  如果很不幸，所有Broker都被认为故障了，这个时候就通过队列，随机选一个broker，属于兜底策略
            return tpInfo.selectOneMessageQueue();
        }
        // 当sendLatencyFaultEnable为false时，选择的方法selectOneMessageQueue
        return tpInfo.selectOneMessageQueue(lastBrokerName);
    }

    public void updateFaultItem(final String brokerName, final long currentLatency, boolean isolation) {
        if (this.sendLatencyFaultEnable) {
            long duration = computeNotAvailableDuration(isolation ? 30000 : currentLatency);
            this.latencyFaultTolerance.updateFaultItem(brokerName, currentLatency, duration);
        }
    }

    /**
     * RocketMQ为每个Broker预测了个可用时间(当前时间+notAvailableDuration)，
     * 当当前时间大于该时间，才代表Broker可用，而notAvailableDuration有6个级
     * 别和latencyMax的区间一一对应，根据传入的currentLatency去预测该Broker在什么时候可用。
     * @param currentLatency
     * @return
     */
    private long computeNotAvailableDuration(final long currentLatency) {
        for (int i = latencyMax.length - 1; i >= 0; i--) {
            if (currentLatency >= latencyMax[i])
                return this.notAvailableDuration[i];
        }

        return 0;
    }
}
