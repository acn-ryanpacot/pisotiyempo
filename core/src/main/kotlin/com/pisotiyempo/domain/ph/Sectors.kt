package com.pisotiyempo.domain.ph

/**
 * PSE sector for commonly traded stocks, used for sector performance and concentration warnings.
 * Neither EODHD's price tier nor the free sources report sectors, so this list is maintained by hand;
 * anything missing shows as "Other". Preferred shares and warrants map through their parent ticker.
 */
object Sectors {
    const val FINANCIALS = "Financials"
    const val INDUSTRIAL = "Industrial"
    const val HOLDING = "Holding Firms"
    const val PROPERTY = "Property"
    const val SERVICES = "Services"
    const val MINING = "Mining & Oil"
    const val ETF = "ETF"
    const val OTHER = "Other"

    val ALL = listOf(FINANCIALS, INDUSTRIAL, HOLDING, PROPERTY, SERVICES, MINING)

    private val MAP: Map<String, String> = buildMap {
        fun sector(name: String, vararg symbols: String) { symbols.forEach { put(it, name) } }
        sector(FINANCIALS, "BDO", "BPI", "MBT", "SECB", "CBC", "PNB", "UBP", "RCB", "EW", "PSB", "AUB", "BNCOM", "PBC", "PBB", "COL", "PSE", "SLF", "MFC")
        sector(INDUSTRIAL, "MER", "AP", "FGEN", "MWC", "JFC", "URC", "MONDE", "CNPF", "EMI", "ACEN", "SPNEC", "PCOR", "SHLPH", "CHP", "EEI", "DNL", "CIC", "RFM", "PIZZA", "FB", "GSMI", "AXLM", "MWIDE", "VITA", "TECH", "ION", "IMI", "PERC", "ALTER", "CREC", "VMC", "ROX")
        sector(HOLDING, "SM", "AC", "AEV", "JGS", "GTCAP", "DMC", "LTG", "SMC", "MPI", "AGI", "ANS", "COSCO", "FPH", "LPZ", "ABA", "FDC", "SGI", "PHN")
        sector(PROPERTY, "SMPH", "ALI", "MEG", "RLC", "FLI", "VLL", "ROCK", "CLI", "DD", "AREIT", "MREIT", "RCR", "DDMPR", "FILRT", "CREIT", "VREIT", "SHNG", "ELI", "HOUSE", "CDC", "CPG", "ALHI", "ARA", "ALCO", "BRN", "GERI", "STR")
        sector(SERVICES, "TEL", "GLO", "CNVRG", "DITO", "ICT", "PGOLD", "RRHI", "SSI", "CEB", "PAL", "BLOOM", "PLUS", "WLCON", "MRSGI", "MAC", "2GO", "DFNN", "NOW", "LBC", "DWC", "FRUIT", "MM", "ABS", "GMA7", "GMAP", "TUGS", "MAXS", "CEU", "IPO", "STI", "FEU")
        sector(MINING, "NIKL", "PX", "APX", "FNI", "MARC", "PXP", "LC", "LCB", "AR", "SCC", "OPM", "OPMB", "OV", "BC", "BCB", "GEO", "ORE", "AB", "DIZ", "UPM", "MA", "MAB", "COAL")
        sector(ETF, "FMETF")
    }

    // Suffixes PSE uses for preferred shares (JFCPB), numbered series (SMC2F) and warrants.
    private val DERIVED_SUFFIX = Regex("^(P[A-Z0-9]{0,3}|[0-9][A-Z0-9]?|W|B)$")

    fun of(symbol: String): String {
        MAP[symbol]?.let { return it }
        for (len in symbol.length - 1 downTo 2) {
            val parent = MAP[symbol.substring(0, len)] ?: continue
            if (DERIVED_SUFFIX.matches(symbol.substring(len))) return parent
        }
        return OTHER
    }
}
