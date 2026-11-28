package com.fairuniversal.offlineotp.Utilities

/** Native ownership adapter; syntax, decoding and bounds are portable shared decisions. */
object OtpUriParser {
    @JvmStatic fun parse(input: String?): OtpParseResult = when (val result = com.fairuniversal.offlineotp.domain.ProvisioningCodec.parse(input)) {
        is com.fairuniversal.offlineotp.domain.ProvisioningResult.Failure -> OtpParseResult.Failure(OtpParseError.valueOf(result.error.name))
        is com.fairuniversal.offlineotp.domain.ProvisioningResult.Success -> {
            try { OtpParseResult.Success(adoptPortableOtp(result.value)) } finally { result.value.destroy() }
        }
    }
}
internal fun adoptPortableOtp(value: com.fairuniversal.offlineotp.domain.PortableOtp): ValidatedOtp {
    val seed = value.copySecret()
    return try { ValidatedOtp.create(value.kind, seed, value.period, value.digits, value.counter,
        value.issuer, value.label, value.algorithm, value.tags) } finally { seed.fill(0) }
}
