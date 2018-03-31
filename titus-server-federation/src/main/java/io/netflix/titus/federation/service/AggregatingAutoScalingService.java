/*
 * Copyright 2018 Netflix, Inc.
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
 */
package io.netflix.titus.federation.service;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.protobuf.Empty;
import com.netflix.titus.grpc.protogen.AutoScalingServiceGrpc;
import com.netflix.titus.grpc.protogen.DeletePolicyRequest;
import com.netflix.titus.grpc.protogen.GetPolicyResult;
import com.netflix.titus.grpc.protogen.Job;
import com.netflix.titus.grpc.protogen.JobId;
import com.netflix.titus.grpc.protogen.JobManagementServiceGrpc;
import com.netflix.titus.grpc.protogen.PutPolicyRequest;
import com.netflix.titus.grpc.protogen.ScalingPolicyID;
import com.netflix.titus.grpc.protogen.UpdatePolicyRequest;
import io.grpc.ManagedChannel;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.StreamObserver;
import io.netflix.titus.api.federation.model.Cell;
import io.netflix.titus.common.grpc.GrpcUtil;
import io.netflix.titus.common.grpc.SessionContext;
import io.netflix.titus.common.util.tuple.Triple;
import io.netflix.titus.federation.startup.GrpcConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Completable;
import rx.Observable;

import static com.netflix.titus.grpc.protogen.AutoScalingServiceGrpc.AutoScalingServiceStub;
import static com.netflix.titus.grpc.protogen.JobManagementServiceGrpc.JobManagementServiceStub;
import static io.netflix.titus.common.grpc.GrpcUtil.createWrappedStub;

@Singleton
public class AggregatingAutoScalingService implements AutoScalingService {
    private static final Logger logger = LoggerFactory.getLogger(AggregatingAutoScalingService.class);
    private CellConnector connector;
    private SessionContext sessionContext;
    private GrpcConfiguration grpcConfiguration;

    @Inject
    public AggregatingAutoScalingService(CellConnector connector, SessionContext sessionContext,
                                         GrpcConfiguration configuration) {
        this.connector = connector;
        this.sessionContext = sessionContext;
        grpcConfiguration = configuration;
    }

    @Override
    public Observable<GetPolicyResult> getJobScalingPolicies(JobId jobId) {
        Observable<Triple<Cell, AutoScalingServiceStub, GetPolicyResult>> allCellsResult = callToAllCells(AutoScalingServiceGrpc::newStub,
                (AutoScalingServiceStub client, StreamObserver<GetPolicyResult> responseObserver) ->
                        client.getJobScalingPolicies(jobId, responseObserver), true);
        return combinePolicyResults(allCellsResult);
    }

    @Override
    public Observable<ScalingPolicyID> setAutoScalingPolicy(PutPolicyRequest request) {
        JobId jobId = JobId.newBuilder().setId(request.getJobId()).build();
        Observable<Triple<Cell, JobManagementServiceStub, Job>> allCellsResult = callToAllCells(JobManagementServiceGrpc::newStub,
                (JobManagementServiceStub client, StreamObserver<Job> responseObserver) ->
                        client.findJob(jobId, responseObserver), true);

        return allCellsResult
                .flatMap(t -> callCellWithWrappedStub(
                        t.getFirst(),
                        AutoScalingServiceGrpc::newStub,
                        (AutoScalingServiceStub client, StreamObserver<ScalingPolicyID> responseObserver) ->
                                client.setAutoScalingPolicy(request, responseObserver)
                ));
    }

    @Override
    public Observable<GetPolicyResult> getScalingPolicy(ScalingPolicyID request) {
        Observable<Triple<Cell, AutoScalingServiceStub, GetPolicyResult>> allCellsResult = callToAllCells(AutoScalingServiceGrpc::newStub,
                (AutoScalingServiceStub client, StreamObserver<GetPolicyResult> responseObserver) ->
                        client.getScalingPolicy(request, responseObserver), true);
        return combinePolicyResults(allCellsResult);
    }

    @Override
    public Observable<GetPolicyResult> getAllScalingPolicies() {
        Observable<Triple<Cell, AutoScalingServiceStub, GetPolicyResult>> allCellsResult = callToAllCells(AutoScalingServiceGrpc::newStub,
                (AutoScalingServiceStub client, StreamObserver<GetPolicyResult> responseObserver) ->
                        client.getAllScalingPolicies(Empty.getDefaultInstance(), responseObserver), false);
        return combinePolicyResults(allCellsResult);
    }

    @Override
    public Completable deleteAutoScalingPolicy(DeletePolicyRequest request) {
        ScalingPolicyID policyId = request.getId();

        Observable<Triple<Cell, AutoScalingServiceStub, GetPolicyResult>> allCellsResult = callToAllCells(AutoScalingServiceGrpc::newStub,
                (AutoScalingServiceStub client, StreamObserver<GetPolicyResult> responseObserver) ->
                        client.getScalingPolicy(policyId, responseObserver), true);

        return allCellsResult.filter(result -> result.getThird().getItemsCount() > 0)
                .flatMap(t -> callCellWithWrappedStub(
                        t.getFirst(),
                        AutoScalingServiceGrpc::newStub,
                        (AutoScalingServiceStub client, StreamObserver<Empty> responseObserver) ->
                                client.deleteAutoScalingPolicy(request, responseObserver)
                )).toCompletable();
    }

    @Override
    public Completable updateAutoScalingPolicy(UpdatePolicyRequest request) {
        ScalingPolicyID policyId = request.getPolicyId();
        Observable<Triple<Cell, AutoScalingServiceStub, GetPolicyResult>> allCellsResult = callToAllCells(AutoScalingServiceGrpc::newStub,
                (AutoScalingServiceStub client, StreamObserver<GetPolicyResult> responseObserver) ->
                        client.getScalingPolicy(policyId, responseObserver), true);

        return allCellsResult.filter(result -> result.getThird().getItemsCount() > 0)
                .flatMap(p -> callWithWrappedStub(
                        p.getSecond(),
                        (AutoScalingServiceStub client, StreamObserver<Empty> responseObserver) ->
                                client.updateAutoScalingPolicy(request, responseObserver)
                )).toCompletable();
    }

    private <STUB extends AbstractStub<STUB>, RespT> Observable<Triple<Cell, STUB, RespT>> callToAllCells(
            Function<ManagedChannel, STUB> stubFactory,
            BiConsumer<STUB, StreamObserver<RespT>> fnCall, boolean swallowErrors) {
        Map<Cell, STUB> clients = CellConnectorUtil.stubs(connector, stubFactory);

        List<Observable<Triple<Cell, STUB, RespT>>> observables = clients.entrySet().stream().map(entry -> {
            final Cell cell = entry.getKey();
            STUB client = entry.getValue();
            if (swallowErrors) {
                return callWithWrappedStub(client, fnCall)
                        .map(result -> new Triple<>(cell, client, result))
                        .onErrorResumeNext(pair -> Observable.empty());
            }
            return callWithWrappedStub(client, fnCall)
                    .map(result -> new Triple<>(cell, client, result));
        }).collect(Collectors.toList());

        return Observable.mergeDelayError(observables);
    }

    private <STUB extends AbstractStub<STUB>, RespT> Observable<RespT> callWithWrappedStub(
            STUB client,
            BiConsumer<STUB, StreamObserver<RespT>> fnCall) {
        return GrpcUtil.createRequestObservable(emitter -> {
            StreamObserver<RespT> streamObserver = GrpcUtil.createSimpleClientResponseObserver(emitter);
            STUB wrappedStub = createWrappedStub(client, sessionContext, grpcConfiguration.getRequestTimeoutMs());
            fnCall.accept(wrappedStub, streamObserver);
        });
    }

    private <STUB extends AbstractStub<STUB>, RespT> Observable<RespT> callCellWithWrappedStub(
            Cell cell,
            Function<ManagedChannel, STUB> stubFactory,
            BiConsumer<STUB, StreamObserver<RespT>> fnCall) {
        Map<Cell, STUB> clients = CellConnectorUtil.stubs(connector, stubFactory);
        STUB targetClient = clients.get(cell);
        if (targetClient != null) {
            return callWithWrappedStub(targetClient, fnCall);
        } else {
            return Observable.error(new IllegalArgumentException("Invalid Cell " + cell));
        }
    }

    private <STUB> Observable<GetPolicyResult> combinePolicyResults(Observable<Triple<Cell, STUB, GetPolicyResult>> policyResults) {
        return policyResults.reduce(GetPolicyResult.newBuilder().build(),
                (acc, next) -> GetPolicyResult.newBuilder()
                        .addAllItems(acc.getItemsList())
                        .addAllItems(next.getThird().getItemsList()).build());
    }

}