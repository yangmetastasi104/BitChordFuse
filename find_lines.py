path = 'app/src/main/java/com/music/bitchord/playback/PlaybackService.kt'
with open(path, 'r') as f:
    content = f.read()

lines = content.split('\n')
for i, line in enumerate(lines):
    if 'onSourceStream' in line or 'onStreamPicked' in line or 'won.format' in line or 'won.stream.format' in line:
        print(f'{i+1}: {line}')
