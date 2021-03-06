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
 *
 */

package org.apache.skywalking.aop.server.receiver.mesh;

import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.apm.network.servicemesh.v3.Protocol;
import org.apache.skywalking.apm.network.servicemesh.v3.ServiceMeshMetric;
import org.apache.skywalking.apm.util.StringFormatGroup;
import org.apache.skywalking.apm.util.StringUtil;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.analysis.IDManager;
import org.apache.skywalking.oap.server.core.analysis.NodeType;
import org.apache.skywalking.oap.server.core.analysis.TimeBucket;
import org.apache.skywalking.oap.server.core.source.All;
import org.apache.skywalking.oap.server.core.source.DetectPoint;
import org.apache.skywalking.oap.server.core.source.Endpoint;
import org.apache.skywalking.oap.server.core.source.RequestType;
import org.apache.skywalking.oap.server.core.source.Service;
import org.apache.skywalking.oap.server.core.source.ServiceInstance;
import org.apache.skywalking.oap.server.core.source.ServiceInstanceRelation;
import org.apache.skywalking.oap.server.core.source.ServiceInstanceUpdate;
import org.apache.skywalking.oap.server.core.source.ServiceRelation;
import org.apache.skywalking.oap.server.core.source.SourceReceiver;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.telemetry.TelemetryModule;
import org.apache.skywalking.oap.server.telemetry.api.HistogramMetrics;
import org.apache.skywalking.oap.server.telemetry.api.MetricsCreator;
import org.apache.skywalking.oap.server.telemetry.api.MetricsTag;

/**
 * TelemetryDataDispatcher processes the {@link ServiceMeshMetric} format telemetry data, transfers it to source
 * dispatcher.
 */
@Slf4j
public class TelemetryDataDispatcher {
    private static SourceReceiver SOURCE_RECEIVER;

    private static HistogramMetrics MESH_ANALYSIS_METRICS;

    private TelemetryDataDispatcher() {
    }

    public static void init(ModuleManager moduleManager) {
        SOURCE_RECEIVER = moduleManager.find(CoreModule.NAME).provider().getService(SourceReceiver.class);
        MetricsCreator metricsCreator = moduleManager.find(TelemetryModule.NAME)
                                                     .provider()
                                                     .getService(MetricsCreator.class);
        MESH_ANALYSIS_METRICS = metricsCreator.createHistogramMetric(
            "mesh_analysis_latency", "The process latency of service mesh telemetry", MetricsTag.EMPTY_KEY,
            MetricsTag.EMPTY_VALUE
        );
    }

    public static void process(ServiceMeshMetric data) {
        HistogramMetrics.Timer timer = MESH_ANALYSIS_METRICS.createTimer();
        try {
            String service = data.getDestServiceName();
            String endpointName = data.getEndpoint();
            StringFormatGroup.FormatResult formatResult = EndpointNameFormater.format(service, endpointName);
            if (formatResult.isMatch()) {
                data = data.toBuilder().setEndpoint(formatResult.getName()).build();
            }
            if (log.isDebugEnabled()) {
                if (formatResult.isMatch()) {
                    log.debug("Endpoint {} is renamed to {}", endpointName, data.getEndpoint());
                }
            }

            doDispatch(data);
        } finally {
            timer.finish();
        }
    }

    static void doDispatch(ServiceMeshMetric metrics) {
        long minuteTimeBucket = TimeBucket.getMinuteTimeBucket(metrics.getStartTime());

        heartbeat(metrics, minuteTimeBucket);
        if (org.apache.skywalking.apm.network.common.v3.DetectPoint.server.equals(metrics.getDetectPoint())) {
            toAll(metrics, minuteTimeBucket);
            toService(metrics, minuteTimeBucket);
            toServiceInstance(metrics, minuteTimeBucket);
            toEndpoint(metrics, minuteTimeBucket);
        }

        String sourceService = metrics.getSourceServiceName();
        // Don't generate relation, if no source.
        if (StringUtil.isNotEmpty(sourceService)) {
            toServiceRelation(metrics, minuteTimeBucket);
            toServiceInstanceRelation(metrics, minuteTimeBucket);
        }
    }

    private static void heartbeat(ServiceMeshMetric metrics, long minuteTimeBucket) {
        // source
        final String sourceServiceName = metrics.getSourceServiceName();
        final String sourceServiceInstance = metrics.getSourceServiceInstance();
        // Don't generate source heartbeat, if no source.
        if (StringUtil.isNotEmpty(sourceServiceName) && StringUtil.isNotEmpty(sourceServiceInstance)) {
            final ServiceInstanceUpdate serviceInstanceUpdate = new ServiceInstanceUpdate();
            serviceInstanceUpdate.setServiceId(
                IDManager.ServiceID.buildId(sourceServiceName, NodeType.Normal)
            );
            serviceInstanceUpdate.setName(sourceServiceInstance);
            serviceInstanceUpdate.setTimeBucket(minuteTimeBucket);
        }

        // dest
        final String destServiceName = metrics.getDestServiceName();
        final String destServiceInstance = metrics.getDestServiceInstance();
        if (StringUtil.isNotEmpty(destServiceName) && StringUtil.isNotEmpty(destServiceInstance)) {
            final ServiceInstanceUpdate serviceInstanceUpdate = new ServiceInstanceUpdate();
            serviceInstanceUpdate.setServiceId(
                IDManager.ServiceID.buildId(destServiceName, NodeType.Normal)
            );
            serviceInstanceUpdate.setName(destServiceInstance);
            serviceInstanceUpdate.setTimeBucket(minuteTimeBucket);
        }
    }

    private static void toAll(ServiceMeshMetric metrics, long minuteTimeBucket) {
        All all = new All();
        all.setTimeBucket(minuteTimeBucket);
        all.setName(metrics.getDestServiceName());
        all.setServiceInstanceName(metrics.getDestServiceInstance());
        all.setEndpointName(metrics.getEndpoint());
        all.setLatency(metrics.getLatency());
        all.setStatus(metrics.getStatus());
        all.setResponseCode(metrics.getResponseCode());
        all.setType(protocol2Type(metrics.getProtocol()));

        SOURCE_RECEIVER.receive(all);
    }

    private static void toService(ServiceMeshMetric metrics, long minuteTimeBucket) {
        Service service = new Service();
        service.setTimeBucket(minuteTimeBucket);
        service.setName(metrics.getDestServiceName());
        service.setNodeType(NodeType.Normal);
        service.setServiceInstanceName(metrics.getDestServiceInstance());
        service.setEndpointName(metrics.getEndpoint());
        service.setLatency(metrics.getLatency());
        service.setStatus(metrics.getStatus());
        service.setResponseCode(metrics.getResponseCode());
        service.setType(protocol2Type(metrics.getProtocol()));

        SOURCE_RECEIVER.receive(service);
    }

    private static void toServiceRelation(ServiceMeshMetric metrics, long minuteTimeBucket) {
        ServiceRelation serviceRelation = new ServiceRelation();
        serviceRelation.setTimeBucket(minuteTimeBucket);
        serviceRelation.setSourceServiceName(metrics.getSourceServiceName());
        serviceRelation.setSourceServiceNodeType(NodeType.Normal);
        serviceRelation.setSourceServiceInstanceName(metrics.getSourceServiceInstance());
        serviceRelation.setDestServiceName(metrics.getDestServiceName());
        serviceRelation.setDestServiceNodeType(NodeType.Normal);
        serviceRelation.setDestServiceInstanceName(metrics.getDestServiceInstance());
        serviceRelation.setEndpoint(metrics.getEndpoint());
        serviceRelation.setLatency(metrics.getLatency());
        serviceRelation.setStatus(metrics.getStatus());
        serviceRelation.setType(protocol2Type(metrics.getProtocol()));
        serviceRelation.setResponseCode(metrics.getResponseCode());
        serviceRelation.setDetectPoint(detectPointMapping(metrics.getDetectPoint()));
        serviceRelation.setComponentId(protocol2Component(metrics.getProtocol()));

        SOURCE_RECEIVER.receive(serviceRelation);
    }

    private static void toServiceInstance(ServiceMeshMetric metrics, long minuteTimeBucket) {
        ServiceInstance serviceInstance = new ServiceInstance();
        serviceInstance.setTimeBucket(minuteTimeBucket);
        serviceInstance.setName(metrics.getDestServiceInstance());
        serviceInstance.setServiceName(metrics.getDestServiceName());
        serviceInstance.setNodeType(NodeType.Normal);
        serviceInstance.setEndpointName(metrics.getEndpoint());
        serviceInstance.setLatency(metrics.getLatency());
        serviceInstance.setStatus(metrics.getStatus());
        serviceInstance.setResponseCode(metrics.getResponseCode());
        serviceInstance.setType(protocol2Type(metrics.getProtocol()));

        SOURCE_RECEIVER.receive(serviceInstance);
    }

    private static void toServiceInstanceRelation(ServiceMeshMetric metrics, long minuteTimeBucket) {
        ServiceInstanceRelation serviceRelation = new ServiceInstanceRelation();
        serviceRelation.setTimeBucket(minuteTimeBucket);
        serviceRelation.setSourceServiceInstanceName(metrics.getSourceServiceInstance());
        serviceRelation.setSourceServiceName(metrics.getSourceServiceName());
        serviceRelation.setSourceServiceNodeType(NodeType.Normal);
        serviceRelation.setDestServiceInstanceName(metrics.getDestServiceInstance());
        serviceRelation.setDestServiceNodeType(NodeType.Normal);
        serviceRelation.setDestServiceName(metrics.getDestServiceName());
        serviceRelation.setEndpoint(metrics.getEndpoint());
        serviceRelation.setLatency(metrics.getLatency());
        serviceRelation.setStatus(metrics.getStatus());
        serviceRelation.setType(protocol2Type(metrics.getProtocol()));
        serviceRelation.setResponseCode(metrics.getResponseCode());
        serviceRelation.setDetectPoint(detectPointMapping(metrics.getDetectPoint()));
        serviceRelation.setComponentId(protocol2Component(metrics.getProtocol()));

        SOURCE_RECEIVER.receive(serviceRelation);
    }

    private static void toEndpoint(ServiceMeshMetric metrics, long minuteTimeBucket) {
        Endpoint endpoint = new Endpoint();
        endpoint.setTimeBucket(minuteTimeBucket);
        endpoint.setName(metrics.getEndpoint());
        endpoint.setServiceName(metrics.getDestServiceName());
        endpoint.setServiceNodeType(NodeType.Normal);
        endpoint.setServiceInstanceName(metrics.getDestServiceInstance());
        endpoint.setLatency(metrics.getLatency());
        endpoint.setStatus(metrics.getStatus());
        endpoint.setResponseCode(metrics.getResponseCode());
        endpoint.setType(protocol2Type(metrics.getProtocol()));

        SOURCE_RECEIVER.receive(endpoint);
    }

    private static RequestType protocol2Type(Protocol protocol) {
        switch (protocol) {
            case gRPC:
                return RequestType.gRPC;
            case HTTP:
                return RequestType.HTTP;
            case UNRECOGNIZED:
            default:
                return RequestType.RPC;
        }
    }

    private static int protocol2Component(Protocol protocol) {
        switch (protocol) {
            case gRPC:
                // GRPC in component-libraries.yml
                return 23;
            case HTTP:
                // HTTP in component-libraries.yml
                return 49;
            case UNRECOGNIZED:
            default:
                // RPC in component-libraries.yml
                return 50;
        }
    }

    private static DetectPoint detectPointMapping(org.apache.skywalking.apm.network.common.v3.DetectPoint detectPoint) {
        switch (detectPoint) {
            case client:
                return DetectPoint.CLIENT;
            case proxy:
                return DetectPoint.PROXY;
            default:
                return DetectPoint.SERVER;
        }
    }

}
