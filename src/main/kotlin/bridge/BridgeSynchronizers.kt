package bridge

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

// Implementacja synchronizacji używając Semaphore
class SemaphoreBridgeSynchronizer(private val capacity: Int) : BridgeSynchronizer {
    private val bridgeSemaphore = Semaphore(capacity)
    private val directionMutex = Mutex()
    private var currentDirection: Direction? = null
    private val carsOnBridge = AtomicInteger(0)
    private val waitingEast = AtomicInteger(0)
    private val waitingWest = AtomicInteger(0)
    private val statistics = SynchronizationStatistics()

    override suspend fun requestEntry(car: Car) {
        when (car.direction) {
            Direction.EAST_TO_WEST -> waitingEast.incrementAndGet()
            Direction.WEST_TO_EAST -> waitingWest.incrementAndGet()
        }

        val waitingTime = measureTimeMillis {
            directionMutex.withLock {
                while (currentDirection != null && currentDirection != car.direction && carsOnBridge.get() > 0) {
                    delay(100) // Czekaj aż most będzie pusty lub w tym samym kierunku
                }

                if (currentDirection == null || carsOnBridge.get() == 0) {
                    currentDirection = car.direction
                }
            }

            bridgeSemaphore.acquire()
        }

        when (car.direction) {
            Direction.EAST_TO_WEST -> waitingEast.decrementAndGet()
            Direction.WEST_TO_EAST -> waitingWest.decrementAndGet()
        }

        carsOnBridge.incrementAndGet()

        // Aktualizuj statystyki
        statistics.totalWaitingTime += waitingTime
        if (waitingTime > statistics.maxWaitingTime) {
            statistics.maxWaitingTime = waitingTime
        }
    }

    override suspend fun exitBridge(car: Car) {
        carsOnBridge.decrementAndGet()
        bridgeSemaphore.release()

        directionMutex.withLock {
            if (carsOnBridge.get() == 0) {
                currentDirection = null
            }
        }

        // Aktualizuj statystyki
        statistics.totalCarsProcessed++
        statistics.carsPerDirection[car.direction] = statistics.carsPerDirection[car.direction]!! + 1
        statistics.averageWaitingTime = statistics.totalWaitingTime.toDouble() / statistics.totalCarsProcessed
    }

    override fun getCurrentCarsOnBridge(): Int = carsOnBridge.get()

    override fun getWaitingCars(direction: Direction): Int = when (direction) {
        Direction.EAST_TO_WEST -> waitingEast.get()
        Direction.WEST_TO_EAST -> waitingWest.get()
    }

    override fun reset() {
        carsOnBridge.set(0)
        waitingEast.set(0)
        waitingWest.set(0)
        currentDirection = null
    }

    override fun getStatistics(): SynchronizationStatistics = statistics.copy()
}

// Implementacja synchronizacji używając Monitor (Mutex + Conditions)
class MonitorBridgeSynchronizer(private val capacity: Int) : BridgeSynchronizer {
    private val mutex = Mutex()
    private var carsOnBridge = 0
    private var currentDirection: Direction? = null
    private val waitingQueues = mapOf(
        Direction.EAST_TO_WEST to ConcurrentLinkedQueue<CompletableDeferred<Unit>>(),
        Direction.WEST_TO_EAST to ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
    )
    private val statistics = SynchronizationStatistics()

    override suspend fun requestEntry(car: Car) {
        val waitingTime = measureTimeMillis {
            val deferred = CompletableDeferred<Unit>()

            mutex.withLock {
                if (canEnter(car.direction)) {
                    enterBridge(car.direction)
                    deferred.complete(Unit)
                } else {
                    waitingQueues[car.direction]!!.offer(deferred)
                }
            }

            deferred.await()
        }

        // Aktualizuj statystyki
        statistics.totalWaitingTime += waitingTime
        if (waitingTime > statistics.maxWaitingTime) {
            statistics.maxWaitingTime = waitingTime
        }
    }

    private fun canEnter(direction: Direction): Boolean {
        return carsOnBridge < capacity && (currentDirection == null || currentDirection == direction)
    }

    private fun enterBridge(direction: Direction) {
        carsOnBridge++
        currentDirection = direction
    }

    override suspend fun exitBridge(car: Car) {
        mutex.withLock {
            carsOnBridge--

            if (carsOnBridge == 0) {
                currentDirection = null
                // Spróbuj wpuścić samochody z przeciwnego kierunku
                val oppositeQueue = waitingQueues[car.direction.opposite()]!!
                val sameQueue = waitingQueues[car.direction]!!

                // Najpierw sprawdź przeciwny kierunek (fairness)
                if (oppositeQueue.isNotEmpty()) {
                    currentDirection = car.direction.opposite()
                    var entered = 0
                    while (oppositeQueue.isNotEmpty() && entered < capacity) {
                        oppositeQueue.poll()?.complete(Unit)
                        carsOnBridge++
                        entered++
                    }
                } else if (sameQueue.isNotEmpty()) {
                    // Jeśli nie ma z przeciwnego, wpuść z tego samego kierunku
                    currentDirection = car.direction
                    var entered = 0
                    while (sameQueue.isNotEmpty() && entered < capacity) {
                        sameQueue.poll()?.complete(Unit)
                        carsOnBridge++
                        entered++
                    }
                }
            } else if (carsOnBridge < capacity) {
                // Jeśli jest miejsce, wpuść więcej z tego samego kierunku
                val queue = waitingQueues[car.direction]!!
                if (queue.isNotEmpty()) {
                    queue.poll()?.complete(Unit)
                    carsOnBridge++
                }
            }
        }

        // Aktualizuj statystyki
        statistics.totalCarsProcessed++
        statistics.carsPerDirection[car.direction] = statistics.carsPerDirection[car.direction]!! + 1
        statistics.averageWaitingTime = statistics.totalWaitingTime.toDouble() / statistics.totalCarsProcessed
    }

    override fun getCurrentCarsOnBridge(): Int = carsOnBridge

    override fun getWaitingCars(direction: Direction): Int = waitingQueues[direction]!!.size

    override fun reset() {
        carsOnBridge = 0
        currentDirection = null
        waitingQueues.values.forEach { it.clear() }
    }

    override fun getStatistics(): SynchronizationStatistics = statistics.copy()
}

// Implementacja z priorytetem dla kierunku z większą liczbą oczekujących
class PriorityMonitorBridgeSynchronizer(private val capacity: Int) : BridgeSynchronizer {
    private val mutex = Mutex()
    private var carsOnBridge = 0
    private var currentDirection: Direction? = null
    private val waitingQueues = mapOf(
        Direction.EAST_TO_WEST to ConcurrentLinkedQueue<CompletableDeferred<Unit>>(),
        Direction.WEST_TO_EAST to ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
    )
    private val statistics = SynchronizationStatistics()
    private var lastSwitchTime = System.currentTimeMillis()
    private val minDirectionTime = 5000L // Minimum 5 sekund dla każdego kierunku

    override suspend fun requestEntry(car: Car) {
        val waitingTime = measureTimeMillis {
            val deferred = CompletableDeferred<Unit>()

            mutex.withLock {
                if (canEnter(car.direction)) {
                    enterBridge(car.direction)
                    deferred.complete(Unit)
                } else {
                    waitingQueues[car.direction]!!.offer(deferred)
                }
            }

            deferred.await()
        }

        statistics.totalWaitingTime += waitingTime
        if (waitingTime > statistics.maxWaitingTime) {
            statistics.maxWaitingTime = waitingTime
        }
    }

    private fun canEnter(direction: Direction): Boolean {
        return carsOnBridge < capacity && (currentDirection == null || currentDirection == direction)
    }

    private fun enterBridge(direction: Direction) {
        if (currentDirection != direction) {
            lastSwitchTime = System.currentTimeMillis()
        }
        carsOnBridge++
        currentDirection = direction
    }

    override suspend fun exitBridge(car: Car) {
        mutex.withLock {
            carsOnBridge--

            if (carsOnBridge == 0) {
                // Wybierz kierunek z większą liczbą oczekujących
                val eastQueue = waitingQueues[Direction.EAST_TO_WEST]!!
                val westQueue = waitingQueues[Direction.WEST_TO_EAST]!!

                val timeSinceSwitch = System.currentTimeMillis() - lastSwitchTime
                val shouldConsiderSwitch = timeSinceSwitch >= minDirectionTime

                val nextDirection = when {
                    eastQueue.isEmpty() && westQueue.isEmpty() -> null
                    eastQueue.isEmpty() -> Direction.WEST_TO_EAST
                    westQueue.isEmpty() -> Direction.EAST_TO_WEST
                    !shouldConsiderSwitch && currentDirection != null -> currentDirection
                    eastQueue.size > westQueue.size * 2 -> Direction.EAST_TO_WEST
                    westQueue.size > eastQueue.size * 2 -> Direction.WEST_TO_EAST
                    else -> car.direction.opposite() // Fairness - zmień kierunek
                }

                if (nextDirection != null) {
                    currentDirection = nextDirection
                    val queue = waitingQueues[nextDirection]!!
                    var entered = 0
                    while (queue.isNotEmpty() && entered < capacity) {
                        queue.poll()?.complete(Unit)
                        carsOnBridge++
                        entered++
                    }
                } else {
                    currentDirection = null
                }
            }
        }

        statistics.totalCarsProcessed++
        statistics.carsPerDirection[car.direction] = statistics.carsPerDirection[car.direction]!! + 1
        statistics.averageWaitingTime = statistics.totalWaitingTime.toDouble() / statistics.totalCarsProcessed
    }

    override fun getCurrentCarsOnBridge(): Int = carsOnBridge

    override fun getWaitingCars(direction: Direction): Int = waitingQueues[direction]!!.size

    override fun reset() {
        carsOnBridge = 0
        currentDirection = null
        waitingQueues.values.forEach { it.clear() }
        lastSwitchTime = System.currentTimeMillis()
    }

    override fun getStatistics(): SynchronizationStatistics = statistics.copy()
}

// Implementacja Semaphore z mechanizmem fairness
class FairnessSemaphoreBridgeSynchronizer(private val capacity: Int) : BridgeSynchronizer {
    private val bridgeSemaphore = Semaphore(capacity)
    private val directionMutex = Mutex()
    private var currentDirection: Direction? = null
    private val carsOnBridge = AtomicInteger(0)
    private val waitingCount = mutableMapOf(
        Direction.EAST_TO_WEST to AtomicInteger(0),
        Direction.WEST_TO_EAST to AtomicInteger(0)
    )
    private val statistics = SynchronizationStatistics()
    private var lastDirectionServed: Direction? = null
    private val maxConsecutiveSameDirection = 3
    private var consecutiveSameDirection = 0

    override suspend fun requestEntry(car: Car) {
        waitingCount[car.direction]!!.incrementAndGet()

        val waitingTime = measureTimeMillis {
            directionMutex.withLock {
                while (!canProceed(car.direction)) {
                    delay(100)
                }

                if (currentDirection != car.direction) {
                    consecutiveSameDirection = 0
                }
                currentDirection = car.direction
            }

            bridgeSemaphore.acquire()
        }

        waitingCount[car.direction]!!.decrementAndGet()
        carsOnBridge.incrementAndGet()

        statistics.totalWaitingTime += waitingTime
        if (waitingTime > statistics.maxWaitingTime) {
            statistics.maxWaitingTime = waitingTime
        }
    }

    private fun canProceed(direction: Direction): Boolean {
        if (carsOnBridge.get() == 0) {
            // Most pusty - sprawdź fairness
            val oppositeWaiting = waitingCount[direction.opposite()]!!.get()
            if (oppositeWaiting > 0 && lastDirectionServed == direction &&
                consecutiveSameDirection >= maxConsecutiveSameDirection) {
                return false
            }
            return true
        }

        return currentDirection == direction && carsOnBridge.get() < capacity
    }

    override suspend fun exitBridge(car: Car) {
        carsOnBridge.decrementAndGet()
        bridgeSemaphore.release()

        directionMutex.withLock {
            if (carsOnBridge.get() == 0) {
                lastDirectionServed = currentDirection
                consecutiveSameDirection++
                currentDirection = null
            }
        }

        statistics.totalCarsProcessed++
        statistics.carsPerDirection[car.direction] = statistics.carsPerDirection[car.direction]!! + 1
        statistics.averageWaitingTime = statistics.totalWaitingTime.toDouble() / statistics.totalCarsProcessed
    }

    override fun getCurrentCarsOnBridge(): Int = carsOnBridge.get()

    override fun getWaitingCars(direction: Direction): Int = waitingCount[direction]!!.get()

    override fun reset() {
        carsOnBridge.set(0)
        waitingCount.values.forEach { it.set(0) }
        currentDirection = null
        lastDirectionServed = null
        consecutiveSameDirection = 0
    }

    override fun getStatistics(): SynchronizationStatistics = statistics.copy()
}