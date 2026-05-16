with open('D:/Code/homecams/app/src/main/java/com/homecam/app/detection/EventDetector.kt', 'r', encoding='utf-8') as f:
    content = f.read()

old = '''    fun startAudioDetection() {
        if (!AppSettings.isCryDetectionEnabled(context)) return
        if (isAudioRunning) return

        val classifier = audioClassifier ?: return

        try {
            val tensorAudio = classifier.createInputTensorAudio()
            val sampleRate = tensorAudio.format.sampleRate
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE) return

            val recordingBufferSize = bufferSize * 2
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, recordingBufferSize
            )
            audioRecord?.startRecording()
            isAudioRunning = true

            audioThread = Thread {
                val audioBuffer = ShortArray(bufferSize)

                while (isAudioRunning) {
                    val read = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (read <= 0) continue

                    val shortArray = audioBuffer.copyOf(read)
                    try {
                        tensorAudio.load(shortArray)
                        val results = classifier.classify(tensorAudio)

                        if (results.isNotEmpty()) {
                            for (category in results[0].categories) {
                                if (isCryLabel(category.label) && category.score > 0.3f) {
                                    if (System.currentTimeMillis() - lastCryTriggerTime > cooldownMs) {
                                        lastCryTriggerTime = System.currentTimeMillis()
                                        onEventDetected(\"cry\")
                                    }
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.also { it.start() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }'''

new = '''    fun startAudioDetection() {
        if (!AppSettings.isCryDetectionEnabled(context)) return
        if (isAudioRunning) return

        val classifier = audioClassifier ?: return

        try {
            val tensorAudio = classifier.createInputTensorAudio()
            val sampleRate = tensorAudio.format.sampleRate
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) return

            val modelInputSize = tensorAudio.format.maxLength
            val recordingBufferSize = maxOf(minBufferSize * 2, modelInputSize)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, recordingBufferSize
            )
            audioRecord?.startRecording()
            isAudioRunning = true

            audioThread = Thread {
                val audioBuffer = ShortArray(modelInputSize)

                while (isAudioRunning) {
                    // \u8bfb\u53d6\u5b8c\u6574\u7684\u6a21\u578b\u8f93\u5165\u957f\u5ea6\uff0815600\u91c7\u6837\u70b9 = 0.975\u79d2\uff09
                    var totalRead = 0
                    while (totalRead < modelInputSize) {
                        val read = audioRecord?.read(audioBuffer, totalRead, modelInputSize - totalRead) ?: -1
                        if (read <= 0) {
                            totalRead = 0
                            break
                        }
                        totalRead += read
                    }
                    if (totalRead < modelInputSize) continue

                    try {
                        tensorAudio.load(audioBuffer)
                        val results = classifier.classify(tensorAudio)

                        if (results.isNotEmpty()) {
                            for (category in results[0].categories) {
                                if (isCryLabel(category.label) && category.score > 0.3f) {
                                    if (System.currentTimeMillis() - lastCryTriggerTime > cooldownMs) {
                                        lastCryTriggerTime = System.currentTimeMillis()
                                        onEventDetected(\"cry\")
                                    }
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, \"Audio classify error\", e)
                    }
                }
            }.also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, \"startAudioDetection() FAILED\", e)
        }
    }'''

if old not in content:
    print('FAIL: old string not found')
    exit(1)

content = content.replace(old, new)

with open('D:/Code/homecams/app/src/main/java/com/homecam/app/detection/EventDetector.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print('SUCCESS')
