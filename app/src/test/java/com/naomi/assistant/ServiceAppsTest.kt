package com.naomi.assistant

import com.naomi.assistant.ServiceApps.FOOD
import com.naomi.assistant.ServiceApps.MUSIC
import com.naomi.assistant.ServiceApps.RIDES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceAppsTest {

    @Test fun ridesAreNamedAsWholeWords() {
        assertEquals("uber", ServiceApps.named(RIDES, "get me an uber to the airport")?.id)
        assertEquals("didi", ServiceApps.named(RIDES, "book a DiDi home")?.id)
        assertEquals("didi", ServiceApps.named(RIDES, "chama um didi pra casa")?.id)
        assertNull("a city, not a ride", ServiceApps.named(RIDES, "como está o tempo em Uberlândia"))
        assertNull("food, not a ride", ServiceApps.named(RIDES, "order a pizza on uber eats"))
        assertNull(ServiceApps.named(RIDES, "ubereats"))
        assertNull("Didi the person", ServiceApps.named(RIDES, "liga pra didi"))
        assertNull(ServiceApps.named(RIDES, "search for didi"))
        assertEquals("didi", ServiceApps.named(RIDES, "didi pro trabalho")?.id)
        assertEquals("didi", ServiceApps.named(RIDES, "me leva pro aeroporto de didi")?.id)
    }

    @Test fun theBrainsNamesFindTheirApps() {
        assertEquals("uber eats", ServiceApps.named(FOOD, "uber eats")?.id)
        assertEquals("uber eats", ServiceApps.named(FOOD, "ubereats")?.id)
        assertEquals("uber eats", ServiceApps.named(FOOD, "Uber Eats")?.id)
        assertEquals("doordash", ServiceApps.named(FOOD, "door dash")?.id)
        assertEquals("youtube", ServiceApps.named(MUSIC, "youtube music")?.id)
        assertEquals("youtube", ServiceApps.named(MUSIC, "YT Music")?.id)
        assertEquals("apple music", ServiceApps.named(MUSIC, "apple music")?.id)
        assertNull("an app she no longer knows", ServiceApps.named(MUSIC, "jiosaavn"))
        assertEquals("a saved favourite", "samsung music", ServiceApps.favouriteMusic("Samsung")?.id)
        assertEquals("youtube", ServiceApps.favouriteMusic("YouTube")?.id)
        assertNull("a song", ServiceApps.named(MUSIC, "apple"))
        assertNull("whatever's playing", ServiceApps.named(MUSIC, "music"))
        assertNull(ServiceApps.named(RIDES, ""))
    }

    @Test fun pickPrefersTheNamedAppThenAnInstalledOne() {
        val nothing = { _: String -> false }
        assertEquals("didi", ServiceApps.pick(RIDES, "didi", nothing).id)
        assertEquals("uber", ServiceApps.pick(RIDES, "", nothing).id)
        assertEquals("didi", ServiceApps.pick(RIDES, "", { it == "com.didiglobal.passenger" }).id)
        assertEquals("doordash", ServiceApps.pick(FOOD, "", { it == "com.dd.doordash" }).id)
        assertEquals("uber eats", ServiceApps.pick(FOOD, "rapido", nothing).id)
    }

    @Test fun rideRequests() {
        for (said in listOf("get me an uber", "book a cab to the city", "get me a ride home", "chama um uber pra casa",
            "chama um táxi", "pede um carro pra mim", "me leva pro aeroporto de didi", "call me a taxi", "call me an uber",
            "call an uber to the airport", "could you call an uber for me please", "ring me an uber", "naomi, chama um táxi",
            "get me an uber to the airport and tell maria i'm on my way", "order an uber to the food court", "chama o didi pro trabalho")) {
            assertTrue(said, ServiceApps.isRideRequest(said))
        }
        for (said in listOf("order a pizza on uber eats", "a ride of a lifetime", "vou pra escola", "olá naomi",
            "uberlândia fica em minas", "pega o carro", "call didi", "liga pra didi", "fala pra didi que eu tô chegando",
            "como se chama um carro elétrico", "a didi vem hoje", "o didi chegou", "didi is coming over tonight",
            "i want didi to come to the party", "pede a didi pra me buscar", "vou pedir um carro novo de natal")) {
            assertFalse(said, ServiceApps.isRideRequest(said))
        }
    }

    @Test fun foodRequests() {
        for (said in listOf("order food", "order some thai food", "open doordash", "search sushi on uber eats",
            "pede comida", "pede uma pizza no uber eats")) {
            assertTrue(said, ServiceApps.isFoodRequest(said))
        }
        for (said in listOf("get me an uber", "what food do koalas eat", "chama um uber", "eu pedi comida ontem",
            "pede pro joão trazer comida", "email john about the doordash receipt", "ele pede comida todo dia",
            "o que você acha de pedir comida hoje", "order an uber to the food court", "tell maria to order food",
            "note that i need to order food", "não quero pedir comida hoje", "é caro pedir comida no doordash?")) {
            assertFalse(said, ServiceApps.isFoodRequest(said))
        }
    }

    @Test fun destinations() {
        assertEquals("the airport", ServiceApps.destination("get me an uber to the airport"))
        assertEquals("the airport", ServiceApps.destination("i need to get an uber to the airport, thanks"))
        assertEquals("aeroporto", ServiceApps.destination("preciso ir pro aeroporto, chama um uber"))
        assertEquals("aeroporto", ServiceApps.destination("chama um uber pra gente ir pro aeroporto"))
        assertEquals("the app keeps home", "", ServiceApps.destination("chama um uber pra casa"))
        assertEquals("the corner of oxford and crown street", ServiceApps.destination("get me an uber to the corner of oxford and crown street"))
        assertEquals("the airport", ServiceApps.destination("uber to the airport and then to the city"))
        assertEquals("", ServiceApps.destination("chama um uber pra minha mãe"))
        assertEquals("shopping", ServiceApps.destination("chama um uber pra minha mãe ir pro shopping"))
        assertEquals("", ServiceApps.destination("chama um uber e manda mensagem pra maria que eu tô indo"))
        assertTrue(ServiceApps.isHome("minha casa"))
        assertTrue(ServiceApps.isHome("home"))
        assertFalse(ServiceApps.isHome("casa da ana"))
        assertEquals("12 george street", ServiceApps.destination("book a didi to 12 George Street please"))
        assertEquals("bondi beach", ServiceApps.destination("I want to go to Bondi Beach in an uber"))
        assertEquals("casa da ana", ServiceApps.destination("chama um uber pra mim pra casa da ana"))
        assertEquals("aeroporto", ServiceApps.destination("me leva pro aeroporto de didi"))
        assertEquals("aeroporto", ServiceApps.destination("uber para o aeroporto agora"))
        assertEquals("shopping", ServiceApps.destination("quero ir pro shopping de uber"))
        assertEquals("", ServiceApps.destination("get me an uber"))
        assertEquals("", ServiceApps.destination("chama um uber pra mim"))
    }

    @Test fun dishes() {
        assertEquals("", ServiceApps.dish("order food on doordash"))
        assertEquals("", ServiceApps.dish("open uber eats"))
        assertEquals("pizza", ServiceApps.dish("order a pizza on DoorDash"))
        assertEquals("sushi", ServiceApps.dish("search for sushi in uber eats"))
        assertEquals("thai", ServiceApps.dish("order me some thai food from doordash please"))
        assertEquals("pizza de calabresa", ServiceApps.dish("pede uma pizza de calabresa no uber eats"))
        assertEquals("", ServiceApps.dish("pede comida pra mim"))
        assertEquals("", ServiceApps.dish("abre o uber eats"))
        assertEquals("pizza", ServiceApps.dish("abre o doordash e procura pizza"))
        assertEquals("pizza", ServiceApps.dish("pesquisa pizza no doordash"))
        assertEquals("pizza", ServiceApps.dish("pede uma pizza do uber eats"))
        assertEquals("sushi", ServiceApps.dish("pede sushi pelo aplicativo do doordash"))
        assertEquals("", ServiceApps.dish("pede comida no uber"))
        assertEquals("an order, not a dish", "", ServiceApps.dish("where is my uber eats order"))
        assertEquals("", ServiceApps.dish("cadê meu pedido do uber eats"))
        assertEquals("", ServiceApps.dish("order the usual on uber eats"))
        assertEquals("mac and cheese", ServiceApps.dish("order mac and cheese on uber eats"))
        assertEquals("fish and chips", ServiceApps.dish("order fish and chips from doordash"))
        assertEquals("frango a passarinho", ServiceApps.dish("pede frango a passarinho"))
        assertEquals("", ServiceApps.dish("i'm hungry, open uber eats"))
        assertEquals("", ServiceApps.dish("is doordash open now"))
        assertEquals("", ServiceApps.dish("abre o uber eats que eu tô com fome"))
        assertEquals("sushi", ServiceApps.dish("quero comer sushi, abre o doordash"))
        assertEquals("hambúrguer", ServiceApps.dish("pede um hambúrguer lá no uber eats"))
    }

    @Test fun withoutTakesTheAppAndItsPreposition() {
        val spotify = ServiceApps.named(MUSIC, "spotify")!!
        assertEquals("believer", ServiceApps.without("believer on spotify", spotify))
        assertEquals("believer", ServiceApps.without("believer on the spotify", spotify))
        assertEquals("believer", ServiceApps.without("believer no spotify", spotify))
        assertEquals("some jazz", ServiceApps.without("spotify some jazz", spotify))
        val youtube = ServiceApps.named(MUSIC, "youtube")!!
        assertEquals("lofi beats", ServiceApps.without("lofi beats from youtube music", youtube))
    }
}
