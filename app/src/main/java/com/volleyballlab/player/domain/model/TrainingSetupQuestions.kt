package com.volleyballlab.player.domain.model

/**
 * Questions from the setup used until 25 November 2026. Nothing asks them any more;
 * a plan saved then still names one, and live coaching reads it.
 */
data class SetupOption(val id: String, val label: String)
data class SetupQuestion(val id: String, val prompt: String, val options: List<SetupOption>)

object TrainingSetupQuestions {
    private fun options(vararg values: Pair<String, String>) = values.map { SetupOption(it.first, it.second) } + SetupOption("not_sure", "I'm not sure yet")
    val questions = listOf(
        SetupQuestion("serving_focus", "What feels hardest about serving?", options("toss" to "A consistent toss", "contact" to "Contacting the ball", "accuracy" to "Keeping it on target")),
        SetupQuestion("receiving_focus", "What would you like to work on when receiving?", options("ready_position" to "Getting ready", "platform" to "My passing platform", "control" to "Controlling the pass")),
        SetupQuestion("setting_focus", "What feels hardest about setting?", options("hand_position" to "Positioning my hands", "contact" to "A clean contact", "accuracy" to "Sending it on target")),
        SetupQuestion("spiking_focus", "Which part of spiking would you like to practice?", options("approach" to "My approach", "timing" to "Timing my movement", "contact" to "Contacting the ball")),
        SetupQuestion("blocking_focus", "Which part of blocking would you like to practice?", options("ready_position" to "My ready position", "timing" to "Timing my movement", "hand_position" to "Positioning my hands")),
        SetupQuestion("general_focus", "Which practice sounds most useful to start with?", options("Serving" to "Sending the ball over the net", "Receiving" to "Controlling an incoming ball", "Setting" to "Setting for a teammate", "Spiking" to "Attacking the ball", "Blocking" to "Practicing a block")),
    )
    val variants = listOf(
        questions[0].copy(id = "serving_consistency", prompt = "What changes most when your serve goes off target?"),
        questions[1].copy(id = "receiving_control", prompt = "What makes the pass hard to control?"),
        questions[2].copy(id = "setting_control", prompt = "What makes your sets difficult to control?"),
        questions[3].copy(id = "spiking_timing", prompt = "Where do you lose the rhythm of your spike?"),
        questions[4].copy(id = "blocking_timing", prompt = "What makes it hard to time your block?"),
    )
    fun skillForQuestion(id: String): Skill? = if (question(id) == null) null else Skill.All.firstOrNull { id.startsWith(it.name.lowercase() + "_") }
    fun question(id: String) = (questions + variants).firstOrNull { it.id == id }
    fun forSkill(skill: Skill) = questions[Skill.All.indexOf(skill)]
}
