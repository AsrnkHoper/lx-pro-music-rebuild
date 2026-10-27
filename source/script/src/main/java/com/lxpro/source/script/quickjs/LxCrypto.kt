package com.lxpro.source.script.quickjs

import android.util.Base64
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * lx 2.0 预加载脚本所需的原生 crypto 工具（preload.js 的 `__lx_native_call__utils_*` 系列）。
 *
 * 语义与 walnut 版 lx-music-mobile 逐一对齐（社区脚本按这套语义调用，不能偏离）：
 * - `str2b64` / `b642buf`：base64 编解码（b642buf 返回 "[1,2,3]" JSON 数组字符串 = JS Uint8Array）
 * - `str2md5`：⚠️ JS 侧先 `encodeURIComponent`，原生侧必须 `URLDecoder.decode` 还原后再 md5
 *   （配对关系，原生侧不得再编码 —— 04 §2.4 易错点）
 * - `aesEncrypt`：入出参全 base64；mode 字符串原样传给 Cipher.getInstance（脚本传什么用什么，
 *   walnut 生产环境即此语义）；iv 不足 16 字节补零、超长截断
 * - `rsaEncrypt`：X509 公钥（base64 裸串），padding 原样传
 *
 * 所有方法**不抛异常**（返回 ""），与 walnut 一致 —— 脚本侧靠空串判断失败。
 */
object LxCrypto {

    fun str2b64(input: String): String = try {
        Base64.encodeToString(input.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    } catch (e: Exception) {
        ""
    }

    fun b642buf(b64: String): String = try {
        val bytes = Base64.decode(b64.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        bytes.joinToString(",", "[", "]") { it.toInt().toString() }
    } catch (e: Exception) {
        ""
    }

    fun str2md5(urlEncoded: String): String = try {
        // JS 侧已 encodeURIComponent，这里 decode 还原原始字符串（walnut 配对语义）
        val raw = URLDecoder.decode(urlEncoded, "UTF-8")
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray(StandardCharsets.UTF_8))
        digest.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        ""
    }

    fun aesEncrypt(dataB64: String, keyB64: String, ivB64: String, mode: String): String = try {
        val cipher = Cipher.getInstance(mode)
        val keyBytes = Base64.decode(keyB64, Base64.DEFAULT)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), zeroPaddedIv(ivB64))
        Base64.encodeToString(cipher.doFinal(Base64.decode(dataB64, Base64.DEFAULT)), Base64.NO_WRAP)
    } catch (e: Exception) {
        ""
    }

    private fun zeroPaddedIv(ivB64: String): IvParameterSpec {
        val iv = if (ivB64.isEmpty()) ByteArray(0) else Base64.decode(ivB64, Base64.DEFAULT)
        val fixed = ByteArray(16)
        System.arraycopy(iv, 0, fixed, 0, iv.size.coerceAtMost(16))
        return IvParameterSpec(fixed)
    }

    fun rsaEncrypt(dataB64: String, publicKeyB64: String, padding: String): String = try {
        val keyBytes = Base64.decode(publicKeyB64.trim().toByteArray(), Base64.DEFAULT)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance(padding)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        Base64.encodeToString(cipher.doFinal(Base64.decode(dataB64, Base64.DEFAULT)), Base64.NO_WRAP)
    } catch (e: Exception) {
        ""
    }
}
