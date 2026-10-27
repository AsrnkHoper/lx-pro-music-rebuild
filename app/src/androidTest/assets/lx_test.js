/*!
 * @name LX Pro 沙箱测试脚本
 * @description 真机仪器测试用：验证 INIT / lx.request 网络代理 / crypto / musicUrl 全链路
 * @version 1.0.0
 * @author lxpro-dev
 */
// 真实 lx 2.0 脚本形态：lx 全局由 preload 的 lx_setup 在脚本执行前装好，
// 脚本只调 lx.on / lx.send（社区脚本同款结构，无 lx_setup 调用）。

lx.on(lx.EVENT_NAMES.request, ({ source, action, info }) => {
  console.log('handler enter action=' + action)
  switch (action) {
    case 'musicUrl':
      // 载荷为 { type, musicInfo } 双层（lx 2.0 真实形态）——社区脚本按
      // info.musicInfo.songmid 解构，与 walnut sendUserApiRequest 对齐
      if (!info.musicInfo || !info.musicInfo.songmid) {
        throw new Error('musicInfo.songmid missing: ' + JSON.stringify(info).slice(0, 200))
      }
      // 链路一：lx.request 走宿主 OkHttp 代理（baidu 状态码 200 即代理通）
      // 链路二：crypto.md5 结果拼进 URL（宿主断言已知 md5 值）
      return new Promise((resolve, reject) => {
        let resp0
        try {
          lx.request('https://www.baidu.com/', { method: 'get', timeout: 8000 }, (err, resp) => {
            try {
              console.log('request cb err=' + err + ' status=' + (resp && resp.statusCode))
              if (err) return reject(new Error('lx.request failed: ' + err.message))
              if (resp.statusCode !== 200) return reject(new Error('bad status: ' + resp.statusCode))
              const md5 = lx.utils.crypto.md5('test')
              console.log('md5=' + md5)
              resolve('https://example.com/audio-' + md5 + '.mp3')
            } catch (e) {
              console.log('cb crash: ' + (e && e.stack))
              reject(e)
            }
          })
        } catch (e) {
          console.log('request throw: ' + (e && e.stack))
          reject(e)
        }
      })
    case 'lyric':
      // lx 2.0 协议要求 handler 必返 Promise（preload 直接 .then，不接裸值）
      return Promise.resolve({ lyric: '[00:00.00]沙箱测试歌词\n[00:05.00]第二行', tlyric: '', rlyric: '', lxlyric: '' })
    case 'pic':
      return Promise.resolve('https://example.com/cover.jpg')
    default:
      throw new Error('unsupported action: ' + action)
  }
})

console.log('handler registered')
lx.send(lx.EVENT_NAMES.inited, {
  status: true,
  openDevTools: false,
  sources: {
    kw: {
      name: '酷我音乐',
      type: 'music',
      actions: ['musicUrl', 'lyric', 'pic'],
      qualitys: ['128k', '320k', 'flac'],
    },
  },
})
