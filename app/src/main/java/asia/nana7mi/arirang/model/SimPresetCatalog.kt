package asia.nana7mi.arirang.model

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
}
