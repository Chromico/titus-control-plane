/*
 * Copyright 2020 Netflix, Inc.
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

package com.netflix.titus.master.mesos.kubeapiserver.direct.taint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.netflix.titus.api.jobmanager.model.job.Job;
import com.netflix.titus.api.jobmanager.model.job.JobFunctions;
import com.netflix.titus.api.jobmanager.model.job.Task;
import com.netflix.titus.api.model.ApplicationSLA;
import com.netflix.titus.api.model.Tier;
import com.netflix.titus.master.service.management.ApplicationSlaManagementService;
import io.kubernetes.client.models.V1Toleration;

@Singleton
public class DefaultTaintTolerationFactory implements TaintTolerationFactory {

    private final ApplicationSlaManagementService capacityManagement;

    @Inject
    public DefaultTaintTolerationFactory(ApplicationSlaManagementService capacityManagement) {
        this.capacityManagement = capacityManagement;
    }

    @Override
    public List<V1Toleration> buildV1Toleration(Job job, Task task) {
        List<V1Toleration> tolerations = new ArrayList<>();

        // Default taints.
        tolerations.add(Tolerations.TOLERATION_VIRTUAL_KUBLET);

        resolveGpuToleration(job).ifPresent(tolerations::add);
        tolerations.add(resolveTierToleration(job));

        return tolerations;
    }

    private Optional<V1Toleration> resolveGpuToleration(Job job) {
        return job.getJobDescriptor().getContainer().getContainerResources().getGpu() > 0
                ? Optional.of(Tolerations.TOLERATION_GPU_INSTANCE)
                : Optional.empty();
    }

    private V1Toleration resolveTierToleration(Job job) {
        String capacityGroupName = JobFunctions.getEffectiveCapacityGroup(job);
        ApplicationSLA capacityGroup = capacityManagement.getApplicationSLA(capacityGroupName);
        if (capacityGroup == null) {
            return Tolerations.TOLERATION_TIER_FLEX;
        }
        return capacityGroup.getTier() == Tier.Critical ? Tolerations.TOLERATION_TIER_CRITICAL : Tolerations.TOLERATION_TIER_FLEX;
    }
}
