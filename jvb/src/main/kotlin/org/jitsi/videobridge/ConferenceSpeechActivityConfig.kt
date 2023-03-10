/*
 * Copyright @ 2021 - Present, 8x8 Inc
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
package org.jitsi.videobridge

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import java.time.Duration

class ConferenceSpeechActivityConfig {
    val recentSpeakersCount: Int by config {
        "videobridge.speech-activity.recent-speakers-count".from(JitsiConfig.newConfig)
    }

    val enableSilenceDetection: Boolean by config {
        "videobridge.speech-activity.enable-silence-detection".from(JitsiConfig.newConfig)
    }

    val silenceDetectionTimeout: Duration by config {
        "videobridge.speech-activity.silence-detection-timeout".from(JitsiConfig.newConfig)
    }

    companion object {
        @JvmField
        val config = ConferenceSpeechActivityConfig()
    }
}
