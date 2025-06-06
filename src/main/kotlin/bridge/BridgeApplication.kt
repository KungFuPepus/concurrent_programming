package bridge

import javafx.application.Application
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.stage.Stage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.File

class BridgeApplication : Application() {

    companion object {
        lateinit var primaryStage: Stage
        lateinit var configuration: BridgeConfiguration

        @JvmStatic
        fun main(args: Array<String>) {
            launch(BridgeApplication::class.java)
        }
    }

    override fun start(stage: Stage) {
        primaryStage = stage

        // Wczytaj konfigurację
        configuration = loadConfiguration()

        // Załaduj FXML
        val loader = FXMLLoader(javaClass.getResource("/bridge_view.fxml"))
        val root = loader.load<javafx.scene.Parent>()

        // Ustaw kontroler
        val controller = loader.getController<BridgeController>()
        controller.initialize(configuration)

        // Utwórz scenę
        val scene = Scene(root, 1200, 800)
        scene.stylesheets.add(javaClass.getResource("/styles.css").toExternalForm())

        stage.title = "Problem Synchronizacji Mostu - PW-23"
        stage.scene = scene
        stage.isResizable = false

        // Obsługa zamknięcia
        stage.setOnCloseRequest {
            controller.stopSimulation()
            Platform.exit()
        }

        stage.show()
    }

    private fun loadConfiguration(): BridgeConfiguration {
        val configFile = File("bridge_config.json")
        return if (configFile.exists()) {
            try {
                val json = Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                }
                json.decodeFromString(configFile.readText())
            } catch (e: Exception) {
                println("Błąd wczytywania konfiguracji: ${e.message}")
                BridgeConfiguration()
            }
        } else {
            // Utwórz domyślną konfigurację
            val defaultConfig = BridgeConfiguration()
            saveConfiguration(defaultConfig)
            defaultConfig
        }
    }

    fun saveConfiguration(config: BridgeConfiguration) {
        try {
            val json = Json {
                prettyPrint = true
                encodeDefaults = true
            }
            File("bridge_config.json").writeText(json.encodeToString(BridgeConfiguration.serializer(), config))
        } catch (e: Exception) {
            println("Błąd zapisywania konfiguracji: ${e.message}")
        }
    }
}

@Serializable
data class BridgeConfiguration(
    val bridgeCapacity: Int = 3,
    val numberOfCarsEast: Int = 5,
    val numberOfCarsWest: Int = 5,
    val minArrivalTime: Int = 1000,
    val maxArrivalTime: Int = 5000,
    val minCrossingTime: Int = 2000,
    val maxCrossingTime: Int = 4000,
    val synchronizationType: SynchronizationType = SynchronizationType.SEMAPHORE
)

@Serializable
enum class SynchronizationType {
    SEMAPHORE,
    MONITOR,
    SEMAPHORE_FAIRNESS,
    MONITOR_PRIORITY
}

enum class Direction {
    EAST_TO_WEST,
    WEST_TO_EAST;

    fun opposite(): Direction = when(this) {
        EAST_TO_WEST -> WEST_TO_EAST
        WEST_TO_EAST -> EAST_TO_WEST
    }

    override fun toString(): String = when(this) {
        EAST_TO_WEST -> "Wschód → Zachód"
        WEST_TO_EAST -> "Zachód → Wschód"
    }
}

data class Car(
    val id: Int,
    val direction: Direction,
    var state: CarState = CarState.WAITING,
    var position: Double = 0.0
)

enum class CarState {
    WAITING,
    ON_BRIDGE,
    CROSSED
}

interface BridgeSynchronizer {
    suspend fun requestEntry(car: Car)
    suspend fun exitBridge(car: Car)
    fun getCurrentCarsOnBridge(): Int
    fun getWaitingCars(direction: Direction): Int
    fun reset()
    fun getStatistics(): SynchronizationStatistics
}

data class SynchronizationStatistics(
    var totalCarsProcessed: Int = 0,
    var totalWaitingTime: Long = 0,
    var maxWaitingTime: Long = 0,
    var averageWaitingTime: Double = 0.0,
    val carsPerDirection: MutableMap<Direction, Int> = mutableMapOf(
        Direction.EAST_TO_WEST to 0,
        Direction.WEST_TO_EAST to 0
    )
)