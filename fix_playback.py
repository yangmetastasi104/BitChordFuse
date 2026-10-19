import re

path = 'app/src/main/java/com/music/bitchord/playback/PlaybackService.kt'
with open(path, 'r') as f:
    content = f.read()

old1 = '''                val sourceName = won.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                    ?: \"Provider\"
                NerdStats.onSourceStream(videoId, won.stream.format, sourceName)
                NerdStats.recordSource(videoId, sourceName)
                StreamChoice.remember(videoId, won, substituted = true)'''
new1 = '''                val sourceName = won.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                    ?: \"Provider\"
                NerdStats.onSourceStream(videoId, won.format, sourceName)
                won.format.kbps?.let { kbps ->
                    NerdStats.onStreamPicked(videoId, kbps, sourceName)
                }
                NerdStats.recordSource(videoId, sourceName)
                StreamChoice.remember(videoId, won, substituted = true)'''

if old1 in content:
    content = content.replace(old1, new1)
    print('Fixed first occurrence')
else:
    print('First NOT found')

old2 = '''            val sourceName = won.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                ?: \"Provider\"
            NerdStats.onSourceStream(videoId, won.stream.format, sourceName)
            NerdStats.recordSource(videoId, sourceName)'''
new2 = '''            val sourceName = won.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                ?: \"Provider\"
            NerdStats.onSourceStream(videoId, won.format, sourceName)
            won.format.kbps?.let { kbps ->
                NerdStats.onStreamPicked(videoId, kbps, sourceName)
            }
            NerdStats.recordSource(videoId, sourceName)'''

if old2 in content:
    content = content.replace(old2, new2)
    print('Fixed second occurrence')
else:
    print('Second NOT found')

with open(path, 'w') as f:
    f.write(content)
print('Done')
