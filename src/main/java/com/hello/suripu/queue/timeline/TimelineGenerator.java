package com.hello.suripu.queue.timeline;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.hello.suripu.api.notifications.PushNotification;
import com.hello.suripu.api.notifications.SleepScore;
import com.hello.suripu.core.flipper.FeatureFlipper;
import com.hello.suripu.core.models.Timeline;
import com.hello.suripu.core.models.TimelineResult;
import com.hello.suripu.core.notifications.sender.NotificationSender;
import com.hello.suripu.coredropwizard.timeline.TimelineProcessor;
import com.librato.rollout.RolloutClient;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Created by kingshy on 1/11/16
 */

public class TimelineGenerator implements Callable<TimelineQueueProcessor.TimelineMessage> {
    private static final Logger LOGGER = LoggerFactory.getLogger(TimelineGenerator.class);

    final private TimelineProcessor timelineProcessor;
    final private TimelineQueueProcessor.TimelineMessage message;
    final private NotificationSender notificationSender;
    final private RolloutClient featureFlipper;

    public TimelineGenerator(
            final TimelineProcessor timelineProcessor,
            final TimelineQueueProcessor.TimelineMessage message,
            final NotificationSender notificationSender,
            final RolloutClient featureFlipper) {
        this.timelineProcessor = timelineProcessor;
        this.message = message;
        this.notificationSender = notificationSender;
        this.featureFlipper = featureFlipper;
    }

    @Override
    public TimelineQueueProcessor.TimelineMessage call() throws Exception {
        try {
            final TimelineProcessor newTimelineProcessor = timelineProcessor.copyMeWithNewUUID(UUID.randomUUID());
            final TimelineResult result = newTimelineProcessor.retrieveTimelinesFast(message.accountId, message.targetDate,Optional.absent(), Optional.absent());


            if (!result.getTimelineLogV2().isEmpty()) {
                final Timeline timeline = result.timelines.get(0);
                if (timeline.score > 0) {
                    if(featureFlipper.userFeatureActive(FeatureFlipper.PUSH_NOTIFICATIONS_ENABLED, message.accountId, Collections.EMPTY_LIST)) {
                        final SleepScore.NewSleepScore sleepScore = SleepScore.NewSleepScore.newBuilder()
                                .setScore(timeline.score)
                                .setDate(timeline.date)
                                .build();

                        final PushNotification.UserPushNotification pushNotification = PushNotification.UserPushNotification.newBuilder()
                                .setAccountId(message.accountId)
                                .setNewSleepScore(sleepScore)
                                .setTimestamp(DateTime.now(DateTimeZone.UTC).getMillis())
                                .build();

                        notificationSender.sendBatch(Lists.newArrayList(pushNotification));
                        LOGGER.debug("action=send-push-sleep-score account_id={} date={}", message.accountId, timeline.date);
                    }
                    LOGGER.debug("account {}, date {}, score {}", message.accountId, message.targetDate, timeline.score);
                } else {
                    LOGGER.debug("account {}, date {}, NO SCORE!", message.accountId, message.targetDate);
                }
                message.setScore(timeline.score);
            }
        } catch (final Exception exception) {
            LOGGER.error("key=consumer-timeline-generator error=timeline-processor msg={} account_id={} night_date={}",
                    exception.getMessage(), message.accountId, message.targetDate);
            message.setEmptyScore();
        }
        return message;
    }
}

