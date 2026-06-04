(function() {
    'use strict';

    const liveStream = document.getElementById('live-stream');
    const streamStatus = document.getElementById('stream-status');
    const eventList = document.getElementById('event-list');
    const videoList = document.getElementById('video-list');
    const playback = document.getElementById('playback');
    const cameraDropdown = document.getElementById('camera-dropdown');
    const cameraStatus = document.getElementById('camera-status');
    const powerToggle = document.getElementById('power-toggle');
    const rotateBtn = document.getElementById('rotate-btn');
    const batteryIndicator = document.getElementById('battery-indicator');

    let currentCameraId = '';
    let isSwitchingCamera = false;
    let cameraPowered = true;
    let rotationAngle = 0;

    // Tab switching
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
            document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
            btn.classList.add('active');
            document.getElementById(btn.dataset.tab + '-tab').classList.add('active');

            if (btn.dataset.tab === 'history') {
                loadVideoList();
            }
        });
    });

    // Stream status
    liveStream.addEventListener('load', () => {
        streamStatus.textContent = '在线';
        streamStatus.style.color = '#4caf50';
    });

    liveStream.addEventListener('error', () => {
        streamStatus.textContent = '连接断开，重连中...';
        streamStatus.style.color = '#f44336';
        setTimeout(() => {
            liveStream.src = '/video?t=' + Date.now();
        }, 3000);
    });

    // Play video in history player
    function playVideo(url) {
        playback.src = url;
        document.getElementById('player-placeholder').style.display = 'none';
        playback.play();
    }

    // Camera selector
    function loadCameraList() {
        fetch('/api/cameras')
            .then(r => r.json())
            .then(data => {
                if (!data.cameras || data.cameras.length === 0) {
                    cameraDropdown.innerHTML = '<option value="">无可用摄像头</option>';
                    return;
                }
                const currentVal = cameraDropdown.value;
                currentCameraId = data.currentCameraId || '';
                cameraDropdown.innerHTML = data.cameras.map(cam =>
                    `<option value="${cam.cameraId}" data-logical="${cam.logicalCameraId}">${cam.label}</option>`
                ).join('');
                cameraDropdown.value = currentCameraId;
            })
            .catch(() => {});
    }

    cameraDropdown.addEventListener('change', function() {
        if (isSwitchingCamera) return;
        const cameraId = this.value;
        if (!cameraId || cameraId === currentCameraId) return;
        const selected = this.options[this.selectedIndex];
        const logicalCameraId = selected.dataset.logical || cameraId;

        isSwitchingCamera = true;
        this.disabled = true;
        cameraStatus.textContent = '切换中...';

        const params = new URLSearchParams();
        params.append('cameraId', cameraId);
        params.append('logicalCameraId', logicalCameraId);

        fetch('/api/camera/switch?' + params.toString())
        .then(r => r.json())
        .then(data => {
            if (data.success) {
                currentCameraId = cameraId;
                liveStream.src = '/video?t=' + Date.now();
                cameraStatus.textContent = data.switching ? '已切换' : '已保存';
            } else {
                cameraStatus.textContent = '失败';
                cameraDropdown.value = currentCameraId;
            }
        })
        .catch(() => {
            cameraStatus.textContent = '网络错误';
            cameraDropdown.value = currentCameraId;
        })
        .finally(() => {
            isSwitchingCamera = false;
            this.disabled = false;
            setTimeout(() => { cameraStatus.textContent = ''; }, 3000);
        });
    });

    // Power toggle
    powerToggle.addEventListener('click', function() {
        const newState = !cameraPowered;
        const action = newState ? 'on' : 'off';
        powerToggle.disabled = true;

        fetch('/api/camera/power?action=' + action)
        .then(r => r.json())
        .then(data => {
            if (data.success) {
                cameraPowered = data.power;
                updatePowerButton();
                if (cameraPowered) {
                    liveStream.src = '/video?t=' + Date.now();
                } else {
                    liveStream.src = '';
                    streamStatus.textContent = '摄像头已关闭';
                    streamStatus.style.color = '#f44336';
                }
            }
        })
        .catch(() => {})
        .finally(() => {
            powerToggle.disabled = false;
        });
    });

    function updatePowerButton() {
        if (cameraPowered) {
            powerToggle.className = 'power-btn on';
            powerToggle.textContent = '\u26A1';
            powerToggle.title = '关闭摄像头';
        } else {
            powerToggle.className = 'power-btn off';
            powerToggle.textContent = '\u26A0';
            powerToggle.title = '打开摄像头';
        }
    }

    // Rotation button
    rotateBtn.addEventListener('click', function() {
        rotationAngle = (rotationAngle + 90) % 360;
        liveStream.style.transform = 'rotate(' + rotationAngle + 'deg)';
        if (rotationAngle === 0) {
            this.classList.remove('active');
            this.title = '旋转画面 0°';
        } else {
            this.classList.add('active');
            this.title = '旋转画面 ' + rotationAngle + '°';
        }
    });

    // Load status periodically
    function updateStatus() {
        fetch('/api/status')
            .then(r => r.json())
            .then(data => {
                if (!data.running) {
                    streamStatus.textContent = '服务未运行';
                    streamStatus.style.color = '#f44336';
                }
                if (data.latest_event) {
                    const time = new Date(data.latest_event_time).toLocaleTimeString('zh-CN');
                    const typeLabel = getEventLabel(data.latest_event, data.latest_event_label || '');
                }
                // Sync power state from status
                if (data.camera_powered !== undefined && data.camera_powered !== cameraPowered) {
                    cameraPowered = data.camera_powered;
                    updatePowerButton();
                }
                // Sync current camera from status
                if (data.current_camera_id && data.current_camera_id !== currentCameraId) {
                    currentCameraId = data.current_camera_id;
                    cameraDropdown.value = currentCameraId;
                }
                // Update battery indicator
                if (data.battery_level !== undefined && data.battery_level >= 0) {
                    batteryIndicator.textContent = data.battery_level + '%';
                    batteryIndicator.className = 'battery';
                    if (data.battery_level <= 15) {
                        batteryIndicator.classList.add('critical');
                    } else if (data.battery_level <= 30) {
                        batteryIndicator.classList.add('low');
                    }
                } else {
                    batteryIndicator.textContent = '--';
                    batteryIndicator.className = 'battery';
                }
            })
            .catch(() => {});
    }

    setInterval(updateStatus, 5000);
    updateStatus();

    // Load event log every 5 seconds
    setInterval(loadEventLog, 5000);
    loadEventLog();

    // Initialize power button
    updatePowerButton();

    // Load camera list periodically
    loadCameraList();
    setInterval(loadCameraList, 30000);

    function getEventLabel(type, label) {
        switch (type) {
            case 'motion': return '人物移动';
            case 'cry': return '婴儿哭声';
            case 'sleep': return '宝宝睡着了';
            case 'wake_up': return '宝宝睡醒了';
            case 'enter': return '有人进入了';
            case 'leave': return '有人离开了';
            case 'fall': return '检测到有人摔倒';
            case 'get_up': return '有人站起来了';
            case 'phone': return '有人在玩手机（' + (label || '50%') + '）';
            default: return type;
        }
    }

    // Load event log (latest 10)
    function loadEventLog() {
        fetch('/api/events')
            .then(r => r.json())
            .then(events => {
                if (!events || events.length === 0) {
                    eventList.innerHTML = '暂无事件';
                    return;
                }
                const last10 = events.slice(-10).reverse();
                eventList.innerHTML = last10.map(e => {
                    const time = new Date(e.time).toLocaleTimeString('zh-CN');
                    const label = getEventLabel(e.type, e.label || '');
                    return '<div class="event-item">' + time + ' ' + label + '</div>';
                }).join('');
            })
            .catch(() => {
                eventList.innerHTML = '加载失败';
            });
    }

    // Load video list
    function loadVideoList(autoPlay = true) {
        fetch('/api/videos')
            .then(r => r.json())
            .then(videos => {
                if (!videos || videos.length === 0) {
                    videoList.innerHTML = '<div class="empty-state">暂无历史视频</div>';
                    return;
                }

                videoList.innerHTML = videos.slice(0, 10).map(v => {
                    const time = new Date(v.timestamp).toLocaleTimeString('zh-CN');
                    const date = new Date(v.timestamp).toLocaleDateString('zh-CN');
                    const icon = {motion: '👤', cry: '🔊', sleep: '💤', wake_up: '😴', enter: '🚶', leave: '🚪', fall: '😵', get_up: '🧍', phone: '📱'}[v.eventType] || '📁';
                    const typeLabel = {motion: '人物移动', cry: '婴儿哭声', sleep: '宝宝睡着了', wake_up: '宝宝睡醒了', enter: '有人进入', leave: '有人离开', fall: '有人摔倒', get_up: '有人站起来了', phone: '玩手机'}[v.eventType] || v.eventType;
                    const displayLabel = v.eventLabel ? typeLabel + ' (' + v.eventLabel + ')' : typeLabel;
                    const size = (v.fileSize / 1024 / 1024).toFixed(1);

                    return `<div class="video-item" data-url="${v.url}" data-filename="${v.fileName}">
                        <img class="video-thumb" src="/api/thumbnails/${v.fileName}" alt="" loading="lazy" onerror="this.style.display='none'">
                        <div class="info">
                            <div class="time">${date} ${time}</div>
                            <div class="detail">${displayLabel} | ${v.durationSec}秒 | ${size}MB</div>
                        </div>
                        <div class="actions">
                            <button class="btn-icon play-btn" title="播放">&#9654;</button>
                            <button class="btn-icon download-btn" title="下载">&#8615;</button>
                        </div>
                    </div>`;
                }).join('');

                // Bind events
                videoList.querySelectorAll('.play-btn').forEach(btn => {
                    btn.addEventListener('click', (e) => {
                        e.stopPropagation();
                        const item = e.target.closest('.video-item');
                        playVideo(item.dataset.url);
                        videoList.querySelectorAll('.video-item').forEach(i => i.classList.remove('active'));
                        item.classList.add('active');
                    });
                });

                // Click video item to play
                videoList.querySelectorAll('.video-item').forEach(item => {
                    item.addEventListener('click', () => {
                        playVideo(item.dataset.url);
                        videoList.querySelectorAll('.video-item').forEach(i => i.classList.remove('active'));
                        item.classList.add('active');
                    });
                });

                // Auto-play first video on initial load only
                if (autoPlay) {
                    const firstItem = videoList.querySelector('.video-item:first-child');
                    if (firstItem) {
                        firstItem.classList.add('active');
                        playVideo(firstItem.dataset.url);
                    }
                }

                videoList.querySelectorAll('.download-btn').forEach(btn => {
                    btn.addEventListener('click', (e) => {
                        const item = e.target.closest('.video-item');
                        const url = item.dataset.url;
                        const fileName = item.dataset.filename;
                        const a = document.createElement('a');
                        a.href = url;
                        a.download = fileName;
                        a.click();
                    });
                });
            })
            .catch(() => {
                videoList.innerHTML = '<div class="empty-state">加载失败</div>';
            });
    }

    // Auto-refresh history when tab is active
    setInterval(() => {
        if (document.getElementById('history-tab').classList.contains('active')) {
            loadVideoList(false);
        }
    }, 15000);

})();
