package asia.nana7mi.arirang.model

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

data class SimPreset(
    val countryName: String,
    val name: String,
    val mcc: String,
    val mnc: String,
    val countryIso: String,
    val carrierName: String,
    val displayName: String,
    val carrierId: Int = -1
)

object SimPresetCatalog {
    private val random = SecureRandom().asKotlinRandom()

    val ALL: List<SimPreset> = listOf(
        SimPreset(
            countryName = "North Korea",
            name = "Koryolink",
            mcc = "467",
            mnc = "05",
            countryIso = "kp",
            carrierName = "Koryolink",
            displayName = "Koryolink",
            carrierId = -1
        ),
        SimPreset(
            countryName = "Russia",
            name = "MTS",
            mcc = "250",
            mnc = "01",
            countryIso = "ru",
            carrierName = "MTS RUS",
            displayName = "MTS RUS",
            carrierId = 1358
        ),
        SimPreset(
            countryName = "China",
            name = "China Mobile",
            mcc = "460",
            mnc = "00",
            countryIso = "cn",
            carrierName = "中国移动",
            displayName = "中国移动",
            carrierId = 1435
        ),
        SimPreset(
            countryName = "China",
            name = "China Unicom",
            mcc = "460",
            mnc = "01",
            countryIso = "cn",
            carrierName = "中国联通",
            displayName = "中国联通",
            carrierId = 1436
        ),
        SimPreset(
            countryName = "China",
            name = "China Telecom",
            mcc = "460",
            mnc = "11",
            countryIso = "cn",
            carrierName = "中国电信",
            displayName = "中国电信",
            carrierId = 1437
        ),
        SimPreset(
            countryName = "USA",
            name = "T-Mobile",
            mcc = "310",
            mnc = "260",
            countryIso = "us",
            carrierName = "T-Mobile",
            displayName = "T-Mobile",
            carrierId = 1
        ),
        SimPreset(
            countryName = "USA",
            name = "AT&T",
            mcc = "310",
            mnc = "410",
            countryIso = "us",
            carrierName = "AT&T",
            displayName = "AT&T",
            carrierId = 1187
        ),
        SimPreset(
            countryName = "Japan",
            name = "Rakuten",
            mcc = "440",
            mnc = "11",
            countryIso = "jp",
            carrierName = "Rakuten",
            displayName = "Rakuten",
            carrierId = 2314
        ),
        SimPreset(
            countryName = "UK",
            name = "Vodafone",
            mcc = "234",
            mnc = "15",
            countryIso = "gb",
            carrierName = "Vodafone UK",
            displayName = "Vodafone UK",
            carrierId = 1450
        ),
        SimPreset(
            countryName = "Australia",
            name = "Telstra",
            mcc = "505",
            mnc = "01",
            countryIso = "au",
            carrierName = "Telstra",
            displayName = "Telstra",
            carrierId = 1191
        ),
        SimPreset(
            countryName = "Germany",
            name = "Deutsche Telekom",
            mcc = "262",
            mnc = "01",
            countryIso = "de",
            carrierName = "Telekom.de",
            displayName = "Telekom.de",
            carrierId = 1515
        )
    )

    fun randomSimInfo(index: Int, randomInstance: Random = random): SimInfo {
        val preset = ALL.random(randomInstance)
        val countryIso = preset.countryIso.lowercase()
        val number = randomPhoneNumber(countryIso, randomInstance)
        val iccId = randomIccid(countryIso, randomInstance)

        return SimInfo(
            id = index + 1,
            iccId = iccId,
            simSlotIndex = index,
            displayName = preset.displayName,
            carrierName = preset.carrierName,
            nameSource = null,
            iconTint = null,
            number = number,
            roaming = 0,
            icon = null,
            mcc = preset.mcc,
            mnc = preset.mnc,
            countryIso = preset.countryIso,
            isEmbedded = false,
            nativeAccessRules = null,
            cardString = "",
            cardId = index,
            isOpportunistic = false,
            groupUuid = null,
            isGroupDisabled = false,
            carrierId = preset.carrierId,
            profileClass = null,
            subType = null,
            groupOwner = "",
            carrierConfigAccessRules = null,
            areUiccApplicationsEnabled = true,
            portIndex = 0,
            usageSetting = 0,
            isExpanded = true
        )
    }

    fun randomIccid(countryIso: String?, randomInstance: Random = random): String {
        val issuer = when (countryIso?.lowercase()) {
            "kp" -> "89850"
            "ru" -> "89701"
            "us" -> "89014"
            "jp" -> "89810"
            "de" -> "89490"
            "gb" -> "89440"
            "au" -> "89610"
            else -> "89860"
        }
        val body = buildString(18) {
            append(issuer)
            while (length < 18) {
                append(randomInstance.nextInt(10))
            }
        }
        return body + luhnCheckDigit(body)
    }

    fun luhnCheckDigit(body: String): Int {
        val sum = body.reversed().mapIndexed { index, char ->
            val digit = char.digitToIntOrNull() ?: 0
            if (index % 2 == 0) {
                val doubled = digit * 2
                if (doubled > 9) doubled - 9 else doubled
            } else {
                digit
            }
        }.sum()
        return (10 - (sum % 10)) % 10
    }

    fun randomPhoneNumber(countryIso: String?, randomInstance: Random = random): String {
        fun digits(n: Int): String = (1..n).map { randomInstance.nextInt(10) }.joinToString("")
        return when (countryIso?.lowercase()) {
            "cn" -> "+861" + listOf("3", "5", "7", "8", "9").random(randomInstance) + digits(9)
            "us" -> "+1" + (2..9).random(randomInstance) + digits(9)
            "ru" -> "+79" + digits(9)
            "jp" -> "+8190" + digits(8)
            "gb" -> "+447" + digits(9)
            "de" -> "+4915" + digits(8)
            "au" -> "+614" + digits(8)
            "kp" -> "+8501912" + digits(4)
            else -> "+1" + (2..9).random(randomInstance) + digits(9)
        }
    }
}
