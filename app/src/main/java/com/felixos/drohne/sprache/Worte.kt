package com.felixos.drohne.sprache

import java.util.Locale

/**
 * Werkzeug für gesprochenen Text: eine einheitliche Form, Verneinung, Zweifel und Zahlen. Die Muster des Pakets arbeiten
 * nur auf der Form von [norm] (klein, ohne Satzzeichen, Umlaute ausgeschrieben), damit Wortgrenzen auf dem Rechner und
 * auf dem Handy gleich gelten (\b sieht Umlaute je nach Laufzeit verschieden).
 */
object Worte {
    fun norm(t: String): String = t.lowercase(Locale.ROOT)
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        .replace('’', '\'').replace('`', '\'').replace('´', '\'')
        .replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Die Liste aus dem früheren Cockpit.lokal() (not, don't, never, no, nicht, kein, nie), um die Beugungen und „…n't“ ergänzt. */
    private val NEIN = Regex("\\b(don't|dont|do not|not|never|no|nicht|nichts|kein|keine|keinen|keinem|keiner|keins|nie|niemals|nimmer|weder|keinesfalls|keineswegs|neither|nor)\\b|n't\\b")
    fun verneint(s: String): Boolean = NEIN.containsMatchIn(s)

    /** Zweifel oder Bedingung („vielleicht“, „maybe“, „if necessary“, „falls“): bei Flugwirksamem geschieht dann nichts. */
    private val ZWEIFEL = Regex("\\b(vielleicht|eventuell|evtl|womoeglich|moeglicherweise|maybe|perhaps|possibly|might|if|unless|falls|wenn|sofern)\\b")
    fun zweifelt(s: String): Boolean = ZWEIFEL.containsMatchIn(s)

    /** Eine höfliche Bitte anzuhalten in Frageform („Can you stop the drone please?“): ein Auftrag, keine Frage nach dem Stand. */
    private val HALT_BITTE = Regex("^(bitte |please )?(can|could|would|will) you (please )?(stop|pause|halt|hold)( (the drone|the plan|the mission|the flight|it|here|now|please|immediately|right))*$|" +
        "^(bitte )?(kannst|koenntest|wuerdest) du (bitte |mal |jetzt |sofort )*((die drohne|den plan|die mission|den flug|sie|ihn|hier) )?(bitte |mal |jetzt |sofort )*(anhalten|stoppen|pausieren)( bitte)?$")
    fun bittetUmHalt(s: String): Boolean = HALT_BITTE.containsMatchIn(s)

    /** Vorzeichen oder eine andere Einheit als Meter an einer Zahl ([text] wie gesprochen, [s] in der Form von [norm]): ein solcher Wert wird nie gesetzt. */
    private val VORZEICHEN = Regex("[-−–]\\s*\\d")
    private val FREMDE_EINHEIT = Regex("\\b(minus|negative|negativ|feet|foot|ft|fuss|cm|mm|km|zentimeter|zentimetern|centimeters?|centimetres?|kilometer|kilometern|kilometers|kilometres?|inch|inches|zoll|yards?|miles?|meile|meilen)\\b")
    fun zahlFremd(text: String, s: String): Boolean = VORZEICHEN.containsMatchIn(text) || FREMDE_EINHEIT.containsMatchIn(s)

    /** Fragewörter aus dem früheren Cockpit.lokal(), dazu wenige weitere; mitten im Satz. */
    private val FRAGEWORT = Regex("\\b(why|should|shall|can|could|would|what|when|warum|wieso|soll|sollte|sollten|kann|koennte|was|wann|ob|waere|wuerde)\\b")
    /** So beginnt eine Frage, auch wenn die Erkennung kein Fragezeichen liefert. */
    private val FRAGEFORM = Regex("^(wie|was|wo|wann|warum|wieso|weshalb|welche\\w*|wer|how|what|where|when|why|which|who|is|are|do|does|did|can|could|ist|sind|hat|habe|haben|kann|darf|soll|gibt)\\b")
    /** Frage oder Zweifel: Fragezeichen im gesprochenen [text], Fragewort oder Frageform in der Form [s]. */
    fun fragt(text: String, s: String): Boolean = text.trim().endsWith("?") || FRAGEWORT.containsMatchIn(s) || FRAGEFORM.containsMatchIn(s)

    private val DE = mapOf("null" to 0, "eins" to 1, "zwei" to 2, "zwo" to 2, "drei" to 3, "vier" to 4, "fuenf" to 5, "sechs" to 6, "sieben" to 7, "acht" to 8, "neun" to 9,
        "zehn" to 10, "elf" to 11, "zwoelf" to 12, "dreizehn" to 13, "vierzehn" to 14, "fuenfzehn" to 15, "sechzehn" to 16, "siebzehn" to 17, "achtzehn" to 18, "neunzehn" to 19)
    private val DE_ZEHNER = mapOf("zwanzig" to 20, "dreissig" to 30, "vierzig" to 40, "fuenfzig" to 50, "sechzig" to 60, "siebzig" to 70, "achtzig" to 80, "neunzig" to 90)
    private val DE_EINER = mapOf("ein" to 1, "zwei" to 2, "drei" to 3, "vier" to 4, "fuenf" to 5, "sechs" to 6, "sieben" to 7, "acht" to 8, "neun" to 9)
    private val EN = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19)
    private val EN_ZEHNER = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val ZIFFERN = Regex("^(\\d{1,6})(m|meter|metern|meters|metres)?$")

    /** Ein deutsches Zahlwort bis 199 („sechzig“, „fünfundzwanzig“, „hundertzwanzig“); null = keins. */
    private fun deutsch(wort: String): Int? {
        var w = wort; var n = 0
        for (h in listOf("einhundert", "hundert")) if (w.startsWith(h)) { n = 100; w = w.removePrefix(h).removePrefix("und"); break }
        if (w.isEmpty()) return if (n > 0) n else null
        DE[w]?.let { return n + it }
        DE_ZEHNER[w]?.let { return n + it }
        val i = w.indexOf("und")
        if (i > 0) { val e = DE_EINER[w.substring(0, i)]; val z = DE_ZEHNER[w.substring(i + 3)]; if (e != null && z != null) return n + e + z }
        return null
    }

    /** Alle Zahlen, die wörtlich im Text stehen: Ziffern („60“, „60m“) und Zahlwörter in Deutsch und Englisch bis 199. */
    fun zahlen(s: String): List<Int> {
        val t = s.split(' ').filter { it.isNotEmpty() }
        val aus = ArrayList<Int>()
        var i = 0
        while (i < t.size) {
            val w = t[i]
            val zif = ZIFFERN.find(w)
            val de = deutsch(w)
            if (zif != null) { aus += zif.groupValues[1].toInt(); i++ }
            else if (de != null) { aus += de; i++ }
            else {
                var n: Int? = null; var j = i
                if ((w == "one" || w == "a") && t.getOrNull(i + 1) == "hundred") { n = 100; j = i + 2 } else if (w == "hundred") { n = 100; j = i + 1 }
                if (n != null && t.getOrNull(j) == "and" && (t.getOrNull(j + 1) in EN_ZEHNER || t.getOrNull(j + 1) in EN)) j++
                val zehner = EN_ZEHNER[t.getOrNull(j)]
                if (zehner != null) {
                    val e = EN[t.getOrNull(j + 1)]?.takeIf { it in 1..9 }
                    n = (n ?: 0) + zehner + (e ?: 0); j += if (e != null) 2 else 1
                } else EN[t.getOrNull(j)]?.let { n = (n ?: 0) + it; j++ }
                if (n != null) { aus += n!!; i = maxOf(j, i + 1) } else i++
            }
        }
        return aus
    }
}
