package com.hello.suripu.queue.timeline;

import com.google.common.base.Optional;
import com.hello.suripu.core.models.Timeline;
import com.hello.suripu.core.models.TimelineFeedback;
import com.hello.suripu.core.models.TimelineResult;
import com.hello.suripu.coredropwizard.timeline.InstrumentedTimelineProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Created by kingshy on 1/11/16
 */

public class TimelineGenerator implements Callable<TimelineQueueProcessor.TimelineMessage> {
    private static final Logger LOGGER = LoggerFactory.getLogger(TimelineGenerator.class);

    final private InstrumentedTimelineProcessor timelineProcessor;
    final private TimelineQueueProcessor.TimelineMessage message;

    public TimelineGenerator(InstrumentedTimelineProcessor timelineProcessor, TimelineQueueProcessor.TimelineMessage message) {
        this.timelineProcessor = timelineProcessor;
        this.message = message;
    }

    @Override
    public TimelineQueueProcessor.TimelineMessage call() throws Exception {
        try {
            final InstrumentedTimelineProcessor newTimelineProcessor = timelineProcessor.copyMeWithNewUUID(UUID.randomUUID());
            final TimelineResult result = newTimelineProcessor.retrieveTimelinesFast(message.accountId, message.targetDate, Optional.<TimelineFeedback>absent());
            if (!result.getTimelineLogV2().isEmpty()) {
                final Timeline timeline = result.timelines.get(0);
                if (timeline.score > 0) {
                    LOGGER.debug("account {}, date {}, score {}", message.accountId, message.targetDate, timeline.score);
                } else {
                    LOGGER.debug("account {}, date {}, NO SCORE!", message.accountId, message.targetDate);
                }
                message.setScore(timeline.score);
            }
        } catch (Exception exception) {
            LOGGER.error("key=consumer-timeline-generator error=timeline-processor msg={} account_id={} night_date={}",
                    exception.getMessage(), message.accountId, message.targetDate);
            message.setEmptyScore();
        }
        return message;
    }
}

