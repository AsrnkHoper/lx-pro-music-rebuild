/*!
 * @name LX Pro 加密桥测试脚本
 * @description 真机仪器测试用：aes-128-cbc / rsa(no padding) 已知向量端到端校验
 * @version 1.0.0
 * @author lxpro-dev
 */
// 入参形态与真实社区脚本一致：全部传字符串（dataToB64 对 string 走 str2b64
// 直通道，无损；字节数组路径的 bytesToString UTF-8 解码理论上可能产生孤代理
// 字符，真实脚本从不这么用——walnut 生产验证过的形态是字符串）。

const RSA_PUB = '-----BEGIN PUBLIC KEY-----\n'
  + 'MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDW1wtbdABNLWKQfoukrQE/UMXs\n'
  + '2ykdmCFQMWKedaNQe1oG61tOdEtToHpRkY0ZGE9HbBiCIvuGknSFfPr1HvUj4R+T\n'
  + 'sOTdUKZaB9TFwH4v0GJ1HOaP8ZXSgDL9M74/JP9vnPsie6gnJV9IkReXxBTXBUAC\n'
  + '5NBTpvFyX3JdcXV6AwIDAQAB\n'
  + '-----END PUBLIC KEY-----'

lx.on(lx.EVENT_NAMES.request, ({ action }) => {
  if (action !== 'musicUrl') return Promise.reject(new Error('only musicUrl'))
  return new Promise((resolve, reject) => {
    try {
      // AES-128-CBC：key/iv/明文固定字符串 → PKCS7 输出确定
      const aes = lx.utils.crypto.aesEncrypt(
        'Hello LX Pro 沙箱!',
        'aes-128-cbc',
        '0011223344556677',
        'ffeeddccbbaa9988',
      )
      const aesHex = lx.utils.buffer.bufToString(aes, 'hex')
      // RSA/ECB/NoPadding：128 个 'A'（0x41，整数 < 模数）→ 模幂输出确定
      const rsa = lx.utils.crypto.rsaEncrypt('A'.repeat(128), RSA_PUB)
      const rsaHex = lx.utils.buffer.bufToString(rsa, 'hex')
      resolve('https://example.com/aes/' + aesHex + '/rsa/' + rsaHex)
    } catch (e) {
      reject(e)
    }
  })
})

lx.send(lx.EVENT_NAMES.inited, {
  status: true,
  openDevTools: false,
  sources: {
    kw: {
      name: '加密桥测试',
      type: 'music',
      actions: ['musicUrl'],
      qualitys: ['128k'],
    },
  },
})
