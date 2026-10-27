/*!
 * @name 定时器探针
 * @description 验证 setTimeout 链路
 * @version 1.0.0
 * @author lxpro-dev
 */
lx.on(lx.EVENT_NAMES.request, ({ action }) => {
  console.log('probe: handler enter')
  return new Promise((resolve, reject) => {
    console.log('probe: scheduling setTimeout 300ms')
    const id = setTimeout(() => {
      console.log('probe: setTimeout FIRED')
      try {
        const m = lx.utils.crypto.md5('ok')
        console.log('probe: md5 after timer = ' + m)
        resolve('https://example.com/timer-' + m + '.mp3')
      } catch (e) { reject(e) }
    }, 300)
    console.log('probe: timer id=' + id)
  })
})
lx.send(lx.EVENT_NAMES.inited, { status: true, openDevTools: false, sources: { kw: { name: 't', type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } } })
