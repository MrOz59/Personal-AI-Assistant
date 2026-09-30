package com.naomi.assistant

/**
 * The ride, food-delivery and music apps Naomi opens, as used where the owner lives (Sydney):
 * the words that name each one, its package, and how a destination or a dish gets into it.
 * Another country's apps are more rows here (in Brazil: 99, com.taxis99, and iFood,
 * br.com.brainweb.ifood), plus their packages in the manifest's <queries>.
 */
internal object ServiceApps {

    /**
     * An app: [id] is how the brain names it and [label] how she says it; [named] finds it in a
     * request. What to fill in (a destination, a dish) goes in through [link], with it in place
     * of "{}", when the app takes one; else by tapping the field labelled one of [fillTargets]
     * (lowercase, split on `|`, found inside the app's own labels, in English and Portuguese
     * since the app speaks the phone's language) and typing. Neither: the app just opens.
     */
    class App(
        val id: String, val label: String, val pkg: String, val named: Regex,
        val fillTargets: String = "", val link: String = "",
    )

    // Where a ride app asks where you're going, and a food app what you want.
    private const val DESTINATION_FIELD = "where to|destination|para onde|destino|search|pesquis|buscar"
    private const val SEARCH_FIELD = "search|pesquis|buscar|craving"

    // Named as whole words: "Uberlândia" isn't a ride. "Uber" alone is a ride; "Uber Eats" is food.
    val RIDES = listOf(
        App("uber", "Uber", "com.ubercab", wordRegex("\\buber\\b(?! ?eats?\\b)"),
            link = "uber://?action=setPickup&pickup=my_location&dropoff[formatted_address]={}"),
        // Didi is also a name ("a Didi vem hoje?", "liga pra Didi"): the app only when it's asked
        // for ("chama um DiDi", "get me a DiDi", "de DiDi", "DiDi pro trabalho"). The brain's "didi"
        // matches by id.
        App("didi", "DiDi", "com.didiglobal.passenger", wordRegex("(?<=\\b(chama|chame|chamar|pede|peça|peca|pedir|abre|abra|abrir)" +
            "( pra mim| para mim)?( um| uma| o)? |\\b(book|order|get)( me| us)? (an?|the) |\\bopen( the)? |" +
            "\\b(call|ring)( me| us)? (an?|the) |\\b(de|by|on|in|via|no|pelo|with|using) (the |o )?)(didi|di di)\\b|" +
            "^(didi|di di)$|^(didi|di di)(?= (pra|pro|para|até|to)\\b)"), DESTINATION_FIELD),
    )

    val FOOD = listOf(
        App("uber eats", "Uber Eats", "com.ubercab.eats", wordRegex("\\buber ?eats?\\b"), SEARCH_FIELD),
        App("doordash", "DoorDash", "com.dd.doordash", wordRegex("\\bdoor ?dash\\b"), SEARCH_FIELD),
    )

    /** In the order tried when none is named and no favourite is saved; the phone's own player last. */
    val MUSIC = listOf(
        App("spotify", "Spotify", "com.spotify.music", wordRegex("\\bspotify\\b")),
        App("youtube", "YouTube Music", "com.google.android.apps.youtube.music", wordRegex("\\b(youtube|yt)( music)?\\b")),
        App("apple music", "Apple Music", "com.apple.android.music", wordRegex("\\bapple music\\b")),
        App("amazon music", "Amazon Music", "com.amazon.mp3", wordRegex("\\bamazon music\\b")),
        App("deezer", "Deezer", "deezer.android.app", wordRegex("\\bdeezer\\b")),
        App("samsung music", "Samsung Music", "com.samsung.android.app.music", wordRegex("\\bsamsung music\\b")),
        // Never named in a request: "play music" is whatever's playing.
        App("", "Music", "com.android.music", Regex("(?!)")),
    )

    /** The app of [apps] that [said] names ("get me a DiDi", or the brain's "uber"), if any. */
    fun named(apps: List<App>, said: String): App? {
        val s = said.trim().lowercase()
        if (s.isEmpty()) return null
        return apps.firstOrNull { it.id == s } ?: apps.firstOrNull { it.named.containsMatchIn(s) }
    }

    /**
     * The music app saved as their favourite ("my music app is Samsung"): by name, or by the
     * brand alone, which only counts here: in a request, "play Apple" is a song.
     */
    fun favouriteMusic(saved: String): App? {
        val s = saved.trim().lowercase()
        return named(MUSIC, s) ?: MUSIC.firstOrNull { s.isNotEmpty() && it.label.lowercase().substringBefore(' ') == s }
    }

    /** Whether [said] asks for a ride or food app: "search sushi on DoorDash" is for the app, not the web. */
    fun namesRideOrFood(said: String): Boolean = asks(null, RIDES, said) || asks(null, FOOD, said)

    /** The app to open: the one named, else the first installed, else the first (its store page). */
    fun pick(apps: List<App>, said: String, installed: (String) -> Boolean): App =
        named(apps, said) ?: apps.firstOrNull { installed(it.pkg) } ?: apps.first()

    // Said before a request, or asked with a helper verb: "naomi, chama…", "pode pedir…".
    private const val PT_OPENER = "^((por favor|naomi|ei|oi)[, ]+)*(me )?"
    private const val PT_HELPER = "\\b(quero|queria|pode|podia|poderia|vamos|bora|dá pra|da pra)"
    // Asked for a car without naming the app: "book a cab", "call me a taxi", "chama um táxi" — not
    // "como se chama um carro" or "vou pedir um carro novo".
    private val RIDE_ASK = wordRegex("\\b(book|get me|order)\\b.*\\b(cab|ride|taxi)\\b|\\b(call|ring)( me| us)? an? (cab|taxi)\\b|" +
        "\\b(get|need) an? (cab|taxi)\\b|" +
        "($PT_OPENER(chama|chame|pede|peça|peca)|$PT_HELPER (chamar|pedir))( pra mim| para mim)? (um|uma) (t[áa]xi|carro|corrida)" +
        "(?=$|[,.!?]| (pra|pro|para|até|agora|por favor)\\b)")
    // "Order food", "pede comida", "quero pedir comida" — not "pedi comida ontem", "ele pede comida
    // todo dia" or "order an Uber to the food court".
    private val FOOD_ASK = wordRegex("\\border\\b((?!\\b(uber|didi|di di|cab|taxi|ride)\\b).)*\\bfood\\b|" +
        "($PT_OPENER(pede|peça|peca|pedir)|$PT_HELPER pedir)( (pra|para) mim)?( (um|uma|uns|umas|alguma))? comida\\b")

    // A request after a call or message verb is for someone else to hear: "call Didi", "email John
    // about the DoorDash receipt", "tell Maria to order food". "Call me an Uber" isn't.
    private val TO_SOMEONE = wordRegex("\\b(call|ring|phone|tell|ask|email|e-mail|text|message|whatsapp|zap|note|" +
        "remind|liga|ligar|ligue|manda|mande|envia|envie|fala|diz|pergunta)\\b")
    private val CALL_FOR_ONE = wordRegex("\\b(call|ring)( me| us)?( (an?|the|one))?\\s*$")
    // Not asking for one: "não quero pedir comida", "what's Uber's number?", "é caro pedir comida no DoorDash?".
    private val NOT_ASKING = wordRegex("\\b(não|nao|don't|do not|never|nunca)\\b|" +
        "^(who|what|what's|why|when|does|do|is|are|quem|o que|quanto|quando|por que|porque|é|será|sera)\\b")

    /** Whether [said] asks for one of [apps], by [ask]'s wording or by naming it. */
    private fun asks(ask: Regex?, apps: List<App>, said: String): Boolean {
        val s = said.lowercase()
        if (NOT_ASKING.containsMatchIn(s)) return false
        val app = named(apps, s)
        val at = ask?.find(s)?.range?.first ?: app?.let { it.named.find(s)?.range?.first ?: 0 } ?: return false
        return !TO_SOMEONE.containsMatchIn(s.substring(0, at).replace(CALL_FOR_ONE, ""))
    }

    fun isRideRequest(said: String): Boolean = asks(RIDE_ASK, RIDES, said)
    fun isFoodRequest(said: String): Boolean = asks(FOOD_ASK, FOOD, said)

    /** [text] without [app]'s name and the "on"/"no" before it: "play Believer on Spotify" → "play Believer". */
    fun without(text: String, app: App): String {
        val m = app.named.find(text) ?: return text
        val before = text.substring(0, m.range.first).replace(BEFORE_APP, "")
        return (before + " " + text.substring(m.range.last + 1)).replace(SPACES, " ").trim()
    }

    // "on the", "no", "pelo aplicativo do", or just "an"/"um" before the app's name.
    private val BEFORE_APP = wordRegex("(\\b(on|from|in|using|with|via|no|na|pelo|pela|com|de|do|da)\\s+" +
        "((the|o|a) )?((app|aplicativo) (of|do|da)\\s+)?)?(\\b(the|an?|o|um|uma)\\s*)?\\s*$")
    private val SPACES = Regex("\\s+")

    // "to the airport", "pra casa", "pro aeroporto", "até a praia" — but "pra mim", "pra gente ir…",
    // "pra minha mãe" and "to go/get/book…" are who it's for and what to do, not where.
    private val DESTINATION = wordRegex("\\b(to|pra|prá|para|pro|pró|até|ate)\\s+" +
        "(?!(go|get|take|book|call|order|eu|ele|ela|você|voce|ir|chamar|pedir|levar)\\b)([^,;]+)")
    private val GOING = wordRegex("^(go|get|head|ir|chegar)\\s+(to|pra|prá|para|pro|pró|até|ate|ao|à|no|na)\\s+")
    private val FOR_US = wordRegex("\\b(pra|prá|para) (mim|nós|nos|a gente|gente|((o|a) )?(meu|minha|meus|minhas|seu|sua) " +
        "(mãe|mae|pai|pais|filho|filha|filhos|esposa|marido|mulher|namorad[oa]|amig[oa]|amigos|vó|vo|vô|avó|avô|irmão|irmã|irmao|irma))\\b")
    // Another leg or another errand: "to the airport and then the hotel", "e manda mensagem pra Maria…".
    // "Oxford and Crown" is one place.
    private val THEN = wordRegex("\\s+(and|e)\\s+(then|depois|após|apos|tell|text|message|call|send|manda|mande|liga|ligue|" +
        "avisa|avise|fala|diz)\\b.*$")
    private val TRAILING = wordRegex("([,;]?\\s+(please|now|right now|thanks|thank you|por favor|agora|obrigad[oa]))+[.!?,;]*$|[.!?,;]+$")
    private val PT_ARTICLE = wordRegex("^(o|a|os|as)\\s+")
    // Home is saved in the app itself: opening it plainly leaves it one tap away.
    private val HOME = wordRegex("^((the|my|o|a|minha) )?(home|place|house|casa)$")

    /** Whether [place] is home ("home", "minha casa"), which the ride app knows better than an address. */
    fun isHome(place: String): Boolean = HOME.matches(place.trim().lowercase().replace(TRAILING, "").trim())

    /**
     * Where a ride request is going, "" if it doesn't say or it's home: "get me an Uber to the
     * airport" → "the airport", "chama um Uber pro aeroporto" → "aeroporto". The words after the
     * app's name first: "preciso ir pro trabalho, chama um Uber" only says it before.
     */
    fun destination(said: String): String {
        val lower = said.lowercase()
        val app = named(RIDES, lower)
        val after = app?.named?.find(lower)?.let { lower.substring(it.range.last + 1) }
        for (part in listOfNotNull(after, app?.let { without(lower, it) } ?: lower)) {
            val m = DESTINATION.find(part.replace(THEN, "").replace(FOR_US, " ").replace(SPACES, " ")) ?: continue
            val place = m.groupValues[3].replace(THEN, "").replace(GOING, "").replace(TRAILING, "")
                .replace(PT_ARTICLE, "").trim()
            return if (isHome(place)) "" else place
        }
        return ""
    }

    // The words around a dish that aren't the dish. Only taken off the ends: "mac and cheese",
    // "frango a passarinho" and "pizza for two" keep theirs.
    private const val FOOD_FILLER = "(open|search for|search|look for|find|order|get|grab|i want|i'd like|want|" +
        "can you|could you|i'm hungry|i'm starving|me|please|now|some|a|an|the|for|food|from|in|on|to|and|is|app|" +
        "(pelo|pela|no|na) (app|aplicativo)( d[oa])?|aplicativo|pra mim|para mim|por favor|agora|quero|queria|pede|" +
        "peça|peca|pedir|procura|procure|procurar|busca|busque|buscar|pesquisa|pesquise|pesquisar|abre|abra|abrir|" +
        "entra|entrar|bora|vamos|comida|comer|((que|porque|pois) )?(eu )?(t[ôo]|estou) (com|morrendo de) fome|v[êe] se tem|" +
        "see if they have|l[áa]|there|o|os|as|e|um|uma|uns|umas|algo|alguma coisa)"
    private val LEADING_FILLER = wordRegex("^$FOOD_FILLER\\b\\s*")
    private val TRAILING_FILLER = wordRegex("\\s*\\b$FOOD_FILLER$")
    private val PUNCTUATION = Regex("[,;.!?]")
    // About an order already made ("where's my order", "cadê meu pedido") or the same again: the app just opens.
    private val ORDER_STATUS = wordRegex("\\b(where|cancel|status|track|onde|cad[êe]|cancela|cancelar|chegou|chega|chegar|" +
        "last time|the usual|again|de novo|de sempre|" +
        "arrive|arrives|arriving)\\b|\\b(my|meu|o|the)( \\S+){0,2} (order|pedido)\\b")

    /** What to look for in a food app: "order a pizza on DoorDash" → "pizza", "" for just "order food". */
    fun dish(said: String): String {
        var s = said.lowercase()
        if (ORDER_STATUS.containsMatchIn(s)) return ""
        (named(FOOD, s))?.let { s = without(s, it) }
        // "Pede comida no Uber": that Uber is Uber Eats.
        (named(RIDES, s))?.let { s = without(s, it) }
        s = s.replace(PUNCTUATION, " ").replace(SPACES, " ").trim()
        while (true) {
            val shorter = s.replace(LEADING_FILLER, "").replace(TRAILING_FILLER, "").trim()
            if (shorter == s) return s
            s = shorter
        }
    }
}
