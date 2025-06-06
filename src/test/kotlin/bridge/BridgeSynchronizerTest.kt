package bridge

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import kotlin.time.Duration.Companion.milliseconds

class BridgeSynchronizerTest {

    private lateinit var testScope: TestScope

    @BeforeEach
    fun setup() {
        testScope = TestScope()
    }

    @Test
    fun `test basic semaphore synchronizer capacity`() = testScope.runTest {
        val synchronizer = SemaphoreBridgeSynchronizer(3)
        val cars = List(5) { Car(it + 1, Direction.EAST_TO_WEST) }

        // Wpuść 3 samochody
        val jobs = cars.take(3).map { car ->
            launch {
                synchronizer.requestEntry(car)
            }
        }

        advanceTimeBy(100.milliseconds)
        assertEquals(3, synchronizer.getCurrentCarsOnBridge())

        // 4. samochód powinien czekać
        val job4 = launch {
            synchronizer.requestEntry(cars[3])
        }

        advanceTimeBy(100.milliseconds)
        assertEquals(3, synchronizer.getCurrentCarsOnBridge())
        assertFalse(job4.isCompleted)

        // Zwolnij miejsce
        synchronizer.exitBridge(cars[0])
        advanceTimeBy(100.milliseconds)

        assertEquals(3, synchronizer.getCurrentCarsOnBridge())
        assertTrue(job4.isCompleted)
    }

    @Test
    fun `test direction conflict in monitor synchronizer`() = testScope.runTest {
        val synchronizer = MonitorBridgeSynchronizer(3)
        val eastCar = Car(1, Direction.EAST_TO_WEST)
        val westCar = Car(2, Direction.WEST_TO_EAST)

        // Pierwszy samochód wjeżdża
        launch { synchronizer.requestEntry(eastCar) }
        advanceTimeBy(100.milliseconds)
        assertEquals(1, synchronizer.getCurrentCarsOnBridge())

        // Samochód z przeciwnego kierunku musi czekać
        val westJob = launch { synchronizer.requestEntry(westCar) }
        advanceTimeBy(100.milliseconds)
        assertEquals(1, synchronizer.getCurrentCarsOnBridge())
        assertFalse(westJob.isCompleted)

        // Po zwolnieniu mostu, drugi samochód może wjechać
        synchronizer.exitBridge(eastCar)
        advanceTimeBy(100.milliseconds)
        assertTrue(westJob.isCompleted)
        assertEquals(1, synchronizer.getCurrentCarsOnBridge())
    }

    @Test
    fun `test fairness in fairness semaphore synchronizer`() = testScope.runTest {
        val synchronizer = FairnessSemaphoreBridgeSynchronizer(1)
        val eastCars = List(5) { Car(it + 1, Direction.EAST_TO_WEST) }
        val westCars = List(5) { Car(it + 10, Direction.WEST_TO_EAST) }

        // Przepuść kilka samochodów ze wschodu
        repeat(3) { i ->
            synchronizer.requestEntry(eastCars[i])
            synchronizer.exitBridge(eastCars[i])
        }

        // Teraz samochód z zachodu powinien mieć priorytet
        val westJob = launch { synchronizer.requestEntry(westCars[0]) }
        val eastJob = launch { synchronizer.requestEntry(eastCars[3]) }

        advanceTimeBy(500.milliseconds)

        // Zachodni samochód powinien wjechać pierwszy (fairness)
        assertTrue(westJob.isCompleted)
        assertFalse(eastJob.isCompleted)
    }

    @Test
    fun `test statistics collection`() = testScope.runTest {
        val synchronizer = SemaphoreBridgeSynchronizer(2)
        val car1 = Car(1, Direction.EAST_TO_WEST)
        val car2 = Car(2, Direction.WEST_TO_EAST)

        synchronizer.requestEntry(car1)
        delay(100)
        synchronizer.exitBridge(car1)

        synchronizer.requestEntry(car2)
        delay(150)
        synchronizer.exitBridge(car2)

        val stats = synchronizer.getStatistics()
        assertEquals(2, stats.totalCarsProcessed)
        assertEquals(1, stats.carsPerDirection[Direction.EAST_TO_WEST])
        assertEquals(1, stats.carsPerDirection[Direction.WEST_TO_EAST])
        assertTrue(stats.averageWaitingTime >= 0)
    }

    @Test
    fun `test concurrent access stress test`() = testScope.runTest {
        val synchronizer = MonitorBridgeSynchronizer(3)
        val totalCars = 20
        val cars = List(totalCars) {
            Car(it + 1, if (it % 2 == 0) Direction.EAST_TO_WEST else Direction.WEST_TO_EAST)
        }

        val jobs = cars.map { car ->
            launch {
                delay((0..100).random().milliseconds)
                synchronizer.requestEntry(car)
                delay(50.milliseconds) // Symulacja przejazdu
                synchronizer.exitBridge(car)
            }
        }

        jobs.joinAll()

        val stats = synchronizer.getStatistics()
        assertEquals(totalCars, stats.totalCarsProcessed)
        assertEquals(0, synchronizer.getCurrentCarsOnBridge())
    }
}