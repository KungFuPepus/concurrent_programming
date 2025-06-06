package bridge

import javafx.animation.AnimationTimer
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.canvas.Canvas
import javafx.scene.control.*
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.text.Font
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

class BridgeController {

    @FXML private lateinit var canvas: Canvas
    @FXML private lateinit var startButton: Button
    @FXML private lateinit var stopButton: Button
    @FXML private lateinit var resetButton: Button
    @FXML private lateinit var saveConfigButton: Button

    @FXML private lateinit var bridgeCapacitySpinner: Spinner<Int>
    @FXML private lateinit var carsEastSpinner: Spinner<Int>
    @FXML private lateinit var carsWestSpinner: Spinner<Int>
    @FXML private lateinit var minArrivalSpinner: Spinner<Int>
    @FXML private lateinit var maxArrivalSpinner: Spinner<Int>
    @FXML private lateinit var minCrossingSpinner: Spinner<Int>
    @FXML private lateinit var maxCrossingSpinner: Spinner<Int>

    @FXML private lateinit var synchronizationComboBox: ComboBox<SynchronizationType>

    @FXML private lateinit var carsOnBridgeLabel: Label
    @FXML private lateinit var waitingEastLabel: Label
    @FXML private lateinit var waitingWestLabel: Label
    @FXML private lateinit var currentDirectionLabel: Label

    @FXML private lateinit var totalCarsLabel: Label
    @FXML private lateinit var avgWaitingTimeLabel: Label
    @FXML private lateinit var maxWaitingTimeLabel: Label
    @FXML private lateinit var carsEastProcessedLabel: Label
    @FXML private lateinit var carsWestProcessedLabel: Label

    @FXML private lateinit var logTextArea: TextArea

    private lateinit var configuration: BridgeConfiguration
    private var synchronizer: BridgeSynchronizer? = null
    private val cars = ConcurrentLinkedQueue<Car>()
    private val simulationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var simulationJob: Job? = null
    private var animationTimer: AnimationTimer? = null

    // Parametry wizualizacji
    private val bridgeStartX = 300.0
    private val bridgeEndX = 900.0
    private val bridgeY = 300.0
    private val bridgeWidth = bridgeEndX - bridgeStartX
    private val bridgeHeight = 100.0
    private val carWidth = 40.0
    private val carHeight = 20.0

    fun initialize(config: BridgeConfiguration) {
        configuration = config
        setupUI()
        setupCanvas()
    }

    private fun setupUI() {
        // Inicjalizacja spinnerów
        bridgeCapacitySpinner.valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10, configuration.bridgeCapacity)
        carsEastSpinner.valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 20, configuration.numberOfCarsEast)
        carsWestSpinner.valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 20, configuration.numberOfCarsWest)
        minArrivalSpinner.valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(100, 10000, configuration.minArrivalTime, 100)
        maxArrivalSpinner.valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(100, 10000, configuration.maxArrivalTime, 100)
        minCrossingSpinner.valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(100, 10000, configuration.minCrossingTime, 100)
        maxCrossingSpinner.valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(100, 10000, configuration.maxCrossingTime, 100)

        // Inicjalizacja ComboBox
        synchronizationComboBox.items.addAll(SynchronizationType.values())
        synchronizationComboBox.value = configuration.synchronizationType

        // Ustaw konwerter dla lepszego wyświetlania
        synchronizationComboBox.converter = object : javafx.util.StringConverter<SynchronizationType>() {
            override fun toString(type: SynchronizationType?): String = when (type) {
                SynchronizationType.SEMAPHORE -> "Semaphore (podstawowy)"
                SynchronizationType.MONITOR -> "Monitor (Mutex + Conditions)"
                SynchronizationType.SEMAPHORE_FAIRNESS -> "Semaphore z mechanizmem fairness"
                SynchronizationType.MONITOR_PRIORITY -> "Monitor z priorytetem"
                null -> ""
            }

            override fun fromString(string: String?): SynchronizationType = SynchronizationType.SEMAPHORE
        }

        // Przyciski
        stopButton.isDisable = true

        startButton.setOnAction { startSimulation() }
        stopButton.setOnAction { stopSimulation() }
        resetButton.setOnAction { resetSimulation() }
        saveConfigButton.setOnAction { saveConfiguration() }
    }

    private fun setupCanvas() {
        val gc = canvas.graphicsContext2D
        drawBridge()
    }

    private fun drawBridge() {
        val gc = canvas.graphicsContext2D

        // Wyczyść canvas
        gc.clearRect(0.0, 0.0, canvas.width, canvas.height)

        // Tło
        gc.fill = Color.LIGHTBLUE
        gc.fillRect(0.0, 0.0, canvas.width, canvas.height)

        // Droga przed i za mostem
        gc.fill = Color.GRAY
        gc.fillRect(0.0, bridgeY, bridgeStartX, bridgeHeight)
        gc.fillRect(bridgeEndX, bridgeY, canvas.width - bridgeEndX, bridgeHeight)

        // Most
        gc.fill = Color.DARKGRAY
        gc.fillRect(bridgeStartX, bridgeY, bridgeWidth, bridgeHeight)

        // Linie na moście
        gc.stroke = Color.YELLOW
        gc.lineWidth = 2.0
        gc.strokeLine(bridgeStartX, bridgeY + bridgeHeight / 2, bridgeEndX, bridgeY + bridgeHeight / 2)

        // Barierki mostu
        gc.stroke = Color.BROWN
        gc.lineWidth = 3.0
        gc.strokeLine(bridgeStartX, bridgeY, bridgeEndX, bridgeY)
        gc.strokeLine(bridgeStartX, bridgeY + bridgeHeight, bridgeEndX, bridgeY + bridgeHeight)

        // Etykiety
        gc.fill = Color.BLACK
        gc.font = Font.font(16.0)
        gc.fillText("WSCHÓD", 50.0, bridgeY - 20)
        gc.fillText("ZACHÓD", canvas.width - 120, bridgeY - 20)
        gc.fillText("MOST", bridgeStartX + bridgeWidth / 2 - 25, bridgeY - 20)
    }

    private fun drawCars() {
        val gc = canvas.graphicsContext2D

        // Narysuj most ponownie
        drawBridge()

        // Narysuj samochody
        cars.forEach { car ->
            val y = when (car.direction) {
                Direction.EAST_TO_WEST -> bridgeY + 15
                Direction.WEST_TO_EAST -> bridgeY + bridgeHeight - 35
            }

            val x = when (car.state) {
                CarState.WAITING -> {
                    when (car.direction) {
                        Direction.EAST_TO_WEST -> bridgeStartX - 60 - (getWaitingPosition(car) * 50)
                        Direction.WEST_TO_EAST -> bridgeEndX + 20 + (getWaitingPosition(car) * 50)
                    }
                }
                CarState.ON_BRIDGE -> {
                    when (car.direction) {
                        Direction.EAST_TO_WEST -> bridgeStartX + (car.position * bridgeWidth)
                        Direction.WEST_TO_EAST -> bridgeEndX - (car.position * bridgeWidth) - carWidth
                    }
                }
                CarState.CROSSED -> {
                    when (car.direction) {
                        Direction.EAST_TO_WEST -> bridgeEndX + 20 + (car.position * 50)
                        Direction.WEST_TO_EAST -> bridgeStartX - 60 - (car.position * 50) - carWidth
                    }
                }
            }

            // Kolor samochodu zależny od kierunku
            gc.fill = when (car.direction) {
                Direction.EAST_TO_WEST -> Color.RED
                Direction.WEST_TO_EAST -> Color.BLUE
            }

            // Narysuj samochód
            gc.fillRect(x, y, carWidth, carHeight)

            // Numer samochodu
            gc.fill = Color.WHITE
            gc.font = Font.font(12.0)
            gc.fillText(car.id.toString(), x + carWidth / 2 - 5, y + carHeight / 2 + 5)
        }

        // Aktualizuj statystyki na ekranie
        updateStatisticsDisplay()
    }

    private fun getWaitingPosition(car: Car): Int {
        return cars.filter {
            it.state == CarState.WAITING &&
                    it.direction == car.direction &&
                    it.id < car.id
        }.count()
    }

    private fun updateStatisticsDisplay() {
        Platform.runLater {
            synchronizer?.let { sync ->
                carsOnBridgeLabel.text = sync.getCurrentCarsOnBridge().toString()
                waitingEastLabel.text = sync.getWaitingCars(Direction.EAST_TO_WEST).toString()
                waitingWestLabel.text = sync.getWaitingCars(Direction.WEST_TO_EAST).toString()

                val stats = sync.getStatistics()
                totalCarsLabel.text = stats.totalCarsProcessed.toString()
                avgWaitingTimeLabel.text = "%.2f ms".format(stats.averageWaitingTime)
                maxWaitingTimeLabel.text = "${stats.maxWaitingTime} ms"
                carsEastProcessedLabel.text = stats.carsPerDirection[Direction.EAST_TO_WEST].toString()
                carsWestProcessedLabel.text = stats.carsPerDirection[Direction.WEST_TO_EAST].toString()
            }
        }
    }

    private fun startSimulation() {
        // Pobierz wartości z UI
        configuration = BridgeConfiguration(
            bridgeCapacity = bridgeCapacitySpinner.value,
            numberOfCarsEast = carsEastSpinner.value,
            numberOfCarsWest = carsWestSpinner.value,
            minArrivalTime = minArrivalSpinner.value,
            maxArrivalTime = maxArrivalSpinner.value,
            minCrossingTime = minCrossingSpinner.value,
            maxCrossingTime = maxCrossingSpinner.value,
            synchronizationType = synchronizationComboBox.value
        )

        // Utwórz odpowiedni synchronizer
        synchronizer = when (configuration.synchronizationType) {
            SynchronizationType.SEMAPHORE -> SemaphoreBridgeSynchronizer(configuration.bridgeCapacity)
            SynchronizationType.MONITOR -> MonitorBridgeSynchronizer(configuration.bridgeCapacity)
            SynchronizationType.SEMAPHORE_FAIRNESS -> FairnessSemaphoreBridgeSynchronizer(configuration.bridgeCapacity)
            SynchronizationType.MONITOR_PRIORITY -> PriorityMonitorBridgeSynchronizer(configuration.bridgeCapacity)
        }

        // Wyczyść poprzednie samochody
        cars.clear()

        // Utwórz samochody
        var carId = 1
        repeat(configuration.numberOfCarsEast) {
            cars.add(Car(carId++, Direction.EAST_TO_WEST))
        }
        repeat(configuration.numberOfCarsWest) {
            cars.add(Car(carId++, Direction.WEST_TO_EAST))
        }

        log("=== Rozpoczęcie symulacji ===")
        log("Typ synchronizacji: ${synchronizationComboBox.value}")
        log("Pojemność mostu: ${configuration.bridgeCapacity}")
        log("Samochody wschód->zachód: ${configuration.numberOfCarsEast}")
        log("Samochody zachód->wschód: ${configuration.numberOfCarsWest}")

        // Uruchom symulację
        startButton.isDisable = true
        stopButton.isDisable = false

        simulationJob = simulationScope.launch {
            cars.forEach { car ->
                launch {
                    // Losowe opóźnienie przed przybyciem
                    delay(Random.nextLong(configuration.minArrivalTime.toLong(), configuration.maxArrivalTime.toLong()))

                    log("Samochód ${car.id} (${car.direction}) przybywa do mostu")

                    // Żądanie wjazdu na most
                    synchronizer!!.requestEntry(car)

                    // Wjazd na most
                    car.state = CarState.ON_BRIDGE
                    log("Samochód ${car.id} (${car.direction}) wjeżdża na most")

                    // Animacja przejazdu
                    val crossingTime = Random.nextLong(configuration.minCrossingTime.toLong(), configuration.maxCrossingTime.toLong())
                    val steps = 20
                    val stepDelay = crossingTime / steps

                    repeat(steps) {
                        car.position = (it + 1) / steps.toDouble()
                        delay(stepDelay)
                    }

                    // Zjazd z mostu
                    synchronizer!!.exitBridge(car)
                    car.state = CarState.CROSSED
                    car.position = 0.0
                    log("Samochód ${car.id} (${car.direction}) zjechał z mostu")
                }
            }
        }

        // Uruchom animację
        animationTimer = object : AnimationTimer() {
            override fun handle(now: Long) {
                drawCars()
            }
        }
        animationTimer?.start()
    }

    fun stopSimulation() {
        simulationJob?.cancel()
        animationTimer?.stop()

        startButton.isDisable = false
        stopButton.isDisable = true

        log("=== Zatrzymano symulację ===")

        // Pokaż końcowe statystyki
        synchronizer?.let { sync ->
            val stats = sync.getStatistics()
            log("\n=== STATYSTYKI KOŃCOWE ===")
            log("Całkowita liczba przejazdów: ${stats.totalCarsProcessed}")
            log("Średni czas oczekiwania: %.2f ms".format(stats.averageWaitingTime))
            log("Maksymalny czas oczekiwania: ${stats.maxWaitingTime} ms")
            log("Przejazdy wschód->zachód: ${stats.carsPerDirection[Direction.EAST_TO_WEST]}")
            log("Przejazdy zachód->wschód: ${stats.carsPerDirection[Direction.WEST_TO_EAST]}")
        }
    }

    private fun resetSimulation() {
        stopSimulation()
        cars.clear()
        synchronizer?.reset()
        drawBridge()
        logTextArea.clear()

        // Wyczyść statystyki
        Platform.runLater {
            carsOnBridgeLabel.text = "0"
            waitingEastLabel.text = "0"
            waitingWestLabel.text = "0"
            currentDirectionLabel.text = "-"
            totalCarsLabel.text = "0"
            avgWaitingTimeLabel.text = "0.00 ms"
            maxWaitingTimeLabel.text = "0 ms"
            carsEastProcessedLabel.text = "0"
            carsWestProcessedLabel.text = "0"
        }
    }

    private fun saveConfiguration() {
        val config = BridgeConfiguration(
            bridgeCapacity = bridgeCapacitySpinner.value,
            numberOfCarsEast = carsEastSpinner.value,
            numberOfCarsWest = carsWestSpinner.value,
            minArrivalTime = minArrivalSpinner.value,
            maxArrivalTime = maxArrivalSpinner.value,
            minCrossingTime = minCrossingSpinner.value,
            maxCrossingTime = maxCrossingSpinner.value,
            synchronizationType = synchronizationComboBox.value
        )

        try {
            val json = Json {
                prettyPrint = true
                encodeDefaults = true
            }
            File("bridge_config.json").writeText(json.encodeToString(BridgeConfiguration.serializer(), config))

            val alert = Alert(Alert.AlertType.INFORMATION)
            alert.title = "Sukces"
            alert.headerText = null
            alert.contentText = "Konfiguracja została zapisana do pliku bridge_config.json"
            alert.showAndWait()
        } catch (e: Exception) {
            val alert = Alert(Alert.AlertType.ERROR)
            alert.title = "Błąd"
            alert.headerText = "Błąd zapisu konfiguracji"
            alert.contentText = e.message
            alert.showAndWait()
        }
    }

    private fun log(message: String) {
        Platform.runLater {
            logTextArea.appendText("${java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))} - $message\n")
        }
    }
}