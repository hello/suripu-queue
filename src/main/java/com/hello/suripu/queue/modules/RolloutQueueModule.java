package com.hello.suripu.queue.modules;

import com.hello.suripu.core.db.FeatureStore;
import com.hello.suripu.core.flipper.DynamoDBAdapter;
import com.hello.suripu.coredropwizard.timeline.InstrumentedTimelineProcessor;
import com.hello.suripu.coredropwizard.timeline.InstrumentedTimelineProcessorV3;
import com.hello.suripu.coredropwizard.timeline.TimelineProcessor;
import com.hello.suripu.queue.timeline.TimelineQueueConsumerManager;
import com.librato.rollout.RolloutAdapter;
import com.librato.rollout.RolloutClient;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;

@Module(injects = {
        InstrumentedTimelineProcessor.class,
        InstrumentedTimelineProcessorV3.class,
        TimelineQueueConsumerManager.class,
        TimelineProcessor.class
})
public class RolloutQueueModule {
    private final FeatureStore featureStore;
    private final Integer pollingIntervalInSeconds;

    public RolloutQueueModule(final FeatureStore featureStore, final Integer pollingIntervalInSeconds) {
        this.featureStore = featureStore;
        this.pollingIntervalInSeconds = pollingIntervalInSeconds;
    }

    @Provides @Singleton
    RolloutAdapter providesRolloutAdapter() {
        return new DynamoDBAdapter(featureStore, pollingIntervalInSeconds);
    }

    @Provides @Singleton
    RolloutClient providesRolloutClient(RolloutAdapter adapter) {
        return new RolloutClient(adapter);
    }
}