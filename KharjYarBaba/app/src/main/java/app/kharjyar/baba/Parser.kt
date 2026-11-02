package app.kharjyar.baba

/** Turns a bank SMS into a transaction. Returns null for anything that is not a money movement (OTP, ads...). */
object Parser {
    data class Result(val debit: Boolean, val rial: Long, val balance: Long?, val merchant: String?)

    private val debitKw = listOf("برداشت", "خرید", "پرداخت", "انتقال از", "کارت به کارت", "حواله", "قبض")
    private val creditKw = listOf("واریز", "دریافت", "سپرده", "به حساب شما")
    private val skipKw = listOf("رمز", "پویا", "OTP", "otp")

    private fun normalize(s: String): String = buildString {
        for (ch in s) append(
            when (ch) {
                in '۰'..'۹' -> '0' + (ch - '۰')
                in '٠'..'٩' -> '0' + (ch - '٠')
                '٬', '،' -> ','
                else -> ch
            }
        )
    }

    fun parse(raw: String): Result? {
        var t = normalize(raw)
        if (skipKw.any { t.contains(it) }) return null

        val balMatch = Regex("مانده[^\\d]{0,12}(\\d[\\d,]*)").find(t)
        val balance = balMatch?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
        if (balMatch != null) t = t.replace(balMatch.value, " ")
        t = t.replace(Regex("\\d{2,4}/\\d{1,2}/\\d{1,2}"), " ")      // dates
            .replace(Regex("\\d{1,2}:\\d{2}(:\\d{2})?"), " ")         // times
            .replace(Regex("\\S*\\*+\\S*"), " ")                      // masked card numbers

        val m = Regex("(\\d[\\d,]*)\\s*(ریال|تومان)").find(t)
            ?: Regex("(\\d{1,3}(?:,\\d{3})+)()").find(t) ?: return null
        val n = m.groupValues[1].replace(",", "").toLongOrNull() ?: return null
        val rial = if (m.groupValues[2] == "تومان") n * 10 else n

        val d = debitKw.map { t.indexOf(it) }.filter { it >= 0 }.minOrNull()
        val c = creditKw.map { t.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (d == null && c == null) return null
        val debit = c == null || (d != null && d < c)

        val merchant = Regex("(?:پذیرنده|فروشگاه|محل|شرح|نزد)[:\\s]+([^\\n]{2,30})").find(t)?.groupValues?.get(1)?.trim()
        return Result(debit, rial, balance, merchant)
    }
}
