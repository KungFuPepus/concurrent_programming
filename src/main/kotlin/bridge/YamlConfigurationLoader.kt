package bridge

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

object YamlConfigurationLoader {

    fun loadFromYaml(filename: String = "bridge_config.yaml"): BridgeConfiguration? {
        val file = File(filename)
        return if (file.exists()) {
            try {
                val yamlContent = file.readText()
                Yaml.default.decodeFromString(yamlContent)
            } catch (e: Exception) {
                println("Błąd wczytywania pliku YAML: ${e.message}")
                null
            }
        } else {
            null
        }
    }

    fun saveToYaml(config: BridgeConfiguration, filename: String = "bridge_config.yaml") {
        try {
            val yamlContent = Yaml.default.encodeToString(BridgeConfiguration.serializer(), config)
            File(filename).writeText(yamlContent)
        } catch (e: Exception) {
            println("Błąd zapisywania pliku YAML: ${e.message}")
        }
    }
}