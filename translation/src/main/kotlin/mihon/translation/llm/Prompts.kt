package mihon.translation.llm

import kotlinx.serialization.json.JsonElement

object Prompts {

    val TRANSLATE_SYSTEM = """
        You translate Korean webtoons (from their English scanlation) into Italian, like a skilled Italian scanlation team.

        Rules:
        - Natural, fluent, colloquial Italian that fits each character. Never word-for-word.
        - Never translate names of people, places, families, kingdoms. Follow the glossary exactly, always.
        - Character introduction captions ("Count of the Avalon Empire Arie Brun Sten (Master)") keep the full
          name exactly as written and in the same position; translate only the description and the rank in
          parentheses ("Conte dell'Impero di Avalon Arie Brun Sten (Maestro)"). Never split a name into titles.
        - For new recurring terms (titles, ranks, skills, items, organizations) choose one Italian rendering, or keep the
          English if Italian readers would expect it, and report it in glossary_add. Report new named characters too.
        - Respect the character sheet: gender agreement and tu/lei/voi between specific characters. Keep the same form
          for the same speaker->listener pair through the whole chapter. Medieval fantasy nobility normally uses "voi"
          towards superiors and nobles of equal rank in formal settings. Emperors and kings are "Sua/Vostra Maestà",
          princes and princesses "Sua/Vostra Altezza", dukes "Vostra Grazia". Report new address relations.
        - Keep the intensity of the English: no vulgarity stronger than the source ("damned" -> "maledetto", not
          swear words). Keep deliberate repetitions ("DAMN IT! DAMN IT!" -> "Dannazione! Dannazione!").
        - Every line must get its own translation in the same position: never move text between ids.
        - Translate idioms and set phrases by meaning, never word by word ("Know your place!" -> "Stai al tuo
          posto!", "buy time" -> "guadagnare tempo", "playtime is over" -> "il gioco è finito").
        - Titles follow the person, not the English wording: an emperor or king is "Maestà" even when the English
          says "Highness".
        - When the gender of a speaker or listener is unclear and the character sheet does not say it, prefer a
          wording that does not mark gender instead of guessing.
        - Lines are OCR output: fix obvious OCR mistakes using context.
        - Casing: the source is often ALL CAPS. ALWAYS write Italian in normal sentence case (capital letter only at the
          start of sentences and for proper nouns), never all caps, except single shouted words or SFX-like interjections.
        - Spelling and grammar must be flawless Italian; double check gender and number agreement using the character sheet.
        - Respect each line's max length when possible, stay concise. Keep stutters (H-HOW -> C-COME), ellipses, emphasis.
        - A sentence can be split across consecutive lines: translate each piece so the pieces still read in order.
        - Classify every line. For sfx, credit, title and noise lines return an empty "it". "sfx" is only for
          onomatopoeia (BOOM, WHOOSH, THUD); small handwritten asides, murmurs and side comments are dialogue.
          "noise" is only for text with no meaning at all: OCR misspellings of real words ("Catwdown" is
          "Calm down") must be fixed and translated.
        - chapter_summary: 2-4 sentences, WRITTEN IN ITALIAN, about what happens (events, revelations, who is where).
        - glossary_add: besides names, add every title, rank, office, place descriptor, skill, item or lore term that is
          likely to come back (e.g. "Imperial Chief Magician", "Middle Realm"), with the Italian you chose.
        - characters_update: include every character who speaks or is mentioned, with gender (infer from pronouns).
        - tm_add: only short phrases likely to recur (battle cries, system messages, catchphrases), max 5.
    """.trimIndent()

    val COMPACT = """

        Also return arc_summary: one Italian summary (max 250 words) merging "Story so far" with these older chapter
        summaries, keeping what matters for later chapters (main plot, relationships, unresolved threads):
    """.trimIndent()

    val REVIEW_SYSTEM = """
        You are the proofreader of an Italian webtoon scanlation team. You get the English lines and the
        Italian draft of one chapter, plus the series glossary and character sheet.

        Fix ONLY real problems:
        - spelling, grammar, agreement (gender/number), wrong verb person or tense
        - garbage tokens: words in other scripts or random English/code fragments inside Italian lines
        - mistranslations (meaning differs from the English), including idioms translated literally
          ("Know your place!" is "Stai al tuo posto!", not "Unisciti al tuo posto!")
        - wrong form of address: tu/voi/lei must be consistent per speaker->listener pair across the chapter and with
          the character sheet; nobles normally use voi towards superiors; Emperor/King are "Maestà", princes "Altezza"
        - glossary violations, names must follow the glossary; never change the number or gender of a named character
        - unnatural or overly literal Italian that a reader would notice, or vulgarity stronger than the English
        Do not rewrite lines that are already fine, do not change style for taste. Keep lines concise.
        Never change who is speaking to whom: keep the person and number of verbs and imperatives as in the English
        ("JUMP!" said to one person stays "Salta!").
        Return only the lines you change, with a short reason in Italian.
    """.trimIndent()

    val LINE_TYPES = listOf("dialogue", "thought", "narration", "system", "caption", "sfx", "credit", "title", "noise")

    val TRANSLATE_SCHEMA: JsonElement = GeminiClient.json.parseToJsonElement(
        """
        {"type":"object","properties":{
          "lines":{"type":"array","items":{"type":"object","properties":{
            "id":{"type":"integer"},
            "type":{"type":"string","enum":${LINE_TYPES.joinToString(",", "[", "]") { "\"$it\"" }}},
            "it":{"type":"string","description":"Italian translation in sentence case"},
            "en":{"type":"string","description":"Only for lines read from an image: the English text"}},
            "required":["id","type","it"]}},
          "context_update":{"type":"object","properties":{
            "glossary_add":{"type":"array","items":{"type":"object","properties":{
              "en":{"type":"string"},"it":{"type":"string"},
              "kind":{"type":"string","enum":["person","place","organization","title","skill","item","creature","term"]},
              "keep":{"type":"boolean"},"note":{"type":"string"}},
              "required":["en","it","kind","keep"]}},
            "characters_update":{"type":"array","items":{"type":"object","properties":{
              "name":{"type":"string"},"gender":{"type":"string","enum":["m","f","unknown"]},
              "role":{"type":"string"},"speech":{"type":"string"},
              "addresses":{"type":"array","items":{"type":"object","properties":{
                "to":{"type":"string"},"form":{"type":"string","enum":["tu","lei","voi"]}},
                "required":["to","form"]}}},
              "required":["name"]}},
            "chapter_summary":{"type":"string","description":"In Italian"},
            "tm_add":{"type":"array","items":{"type":"object","properties":{
              "en":{"type":"string"},"it":{"type":"string"}},"required":["en","it"]}},
            "arc_summary":{"type":"string"}},
            "required":["glossary_add","characters_update","chapter_summary","tm_add"]}},
          "required":["lines","context_update"]}
        """.trimIndent(),
    )

    val REVIEW_SCHEMA: JsonElement = GeminiClient.json.parseToJsonElement(
        """
        {"type":"object","properties":{"fixes":{"type":"array","items":{"type":"object","properties":{
          "id":{"type":"integer"},"it":{"type":"string"},"reason":{"type":"string"}},
          "required":["id","it","reason"]}}},"required":["fixes"]}
        """.trimIndent(),
    )
}
