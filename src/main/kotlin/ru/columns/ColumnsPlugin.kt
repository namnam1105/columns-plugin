package ru.columns

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.Chest
import org.bukkit.block.Container
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class ColumnsPlugin : JavaPlugin(), Listener {
    // Game state
    private val columnPositions = mutableListOf<Location>()
    private var lobbyLocation: Location? = null
    private var isGameRunning = false
    private var playersInGame = mutableSetOf<Player>()
    private var gameTask: BukkitTask? = null
    private var isModeSelection = false
    private var isPlayfieldSelection = false
    private var playfieldCorner1: Location? = null
    private var playfieldCorner2: Location? = null
    private var worldBorderCenter: Location? = null
    private var originalWorldBorderSize: Double = 0.0
    private var worldBorderShrinkTask: BukkitTask? = null
    private var killAllTask: BukkitTask? = null
    private val blockStates = mutableMapOf<Location, BlockState>()
    private val blockInventories = mutableMapOf<Location, List<ItemStack?>>()
    private val entitiesToRemove = mutableSetOf<Entity>()
    private var chestPosition: Location? = null
    private var isChestSelection: Boolean = false

    // Scoreboard variables
    private var gameScoreboard: Scoreboard? = null

    // BossBar variables
    private var itemBossBar: BossBar? = null
    private var currentItemInterval: Int = 0
    private var itemCountdownSeconds: Int = 0
    private var itemCountdownTicks: Int = 0
    private var bossBarTask: BukkitTask? = null

    data class BlockState(val material: Material, val data: Byte = 0)

    override fun onEnable() {
        // Register event listener
        server.pluginManager.registerEvents(this, this)

        // Create data folder if it doesn't exist
        if (!dataFolder.exists()) {
            dataFolder.mkdir()
        }

        // Load game data
        loadData()

        logger.info("ColumnsGame plugin has been enabled!")
    }

    override fun onDisable() {
        // End game if running
        try {
            endGame()
        } catch (e: Exception) {
            println("no end gmae")
        }

        // Save game data
        saveData()

        // Clean up bossbar
        itemBossBar?.removeAll()

        logger.info("ColumnsGame plugin has been disabled!")
    }

    private fun setupChest() {
        val chestPos = chestPosition ?: run {
            Bukkit.broadcastMessage("${ChatColor.RED}Ошибка: позиция сундука не задана!")
            return
        }

        chestPos.world?.loadChunk(chestPos.blockX shr 4, chestPos.blockZ shr 4)

        val block = chestPos.block
        block.type = Material.BARREL

        // Выполнить действия через 1 тик, чтобы блок успел "примениться"
        Bukkit.getScheduler().runTask(this, Runnable {
            val barrelState = block.state as? Barrel ?: run {
                Bukkit.broadcastMessage("${ChatColor.RED}Ошибка: не удалось получить состояние бочки!")
                return@Runnable
            }

            println("[DEBUG] chestLoot содержимое: ${chestLoot.map { it.type }}")

            barrelState.inventory.clear()
            val addedItems = mutableListOf<ItemStack>()

            Bukkit.getScheduler().runTaskLater(this, Runnable {
                val checkBlock = chestPos.block
                if (checkBlock.type == Material.BARREL) {
                    val state = checkBlock.state as? Barrel
                    val items = state?.inventory?.contents?.filterNotNull()
                    repeat(Random.nextInt(4, 11)) {
                        val randomChoice = chestLoot.random()
                        barrelState.inventory.addItem(randomChoice.clone())
                        addedItems.add(randomChoice)
                    }
                }
            }, 100L)
        })
    }



    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {

        when (command.name.lowercase()) {
            "colstart" -> {
                // Разбор аргументов: интервал выдачи предметов и время сужения границы
                var interval = 3 // Значение по умолчанию для интервала (30 сек)
                var shrinkTimeMinutes = 5  // Значение по умолчанию для времени сужения (20 минут)

                if (args.isNotEmpty()) {
                    try {
                        interval = args[0].toInt()
                    } catch (e: NumberFormatException) {
                        sender.sendMessage("${ChatColor.RED}Интервал должен быть числом! Используется значение по умолчанию (30 сек).")
                    }
                }

                if (args.size > 1) {
                    try {
                        shrinkTimeMinutes = args[1].toInt()
                    } catch (e: NumberFormatException) {
                        sender.sendMessage("${ChatColor.RED}Время сужения должно быть числом! Используется значение по умолчанию (20 минут).")
                    }
                }

                if (isGameRunning) {
                    sender.sendMessage("${ChatColor.RED}Игра уже запущена!")
                } else {
                    startGame(interval, shrinkTimeMinutes)
                }
                return true
            }
            "colend" -> {
                if (isGameRunning) {
                    endGame()
                } else {
                    sender.sendMessage("${ChatColor.RED}Игра не запущена!")
                }
                return true
            }
            "assigncols" -> {
                if (sender !is Player) {
                    sender.sendMessage("${ChatColor.RED}Команды должны выполняться игроком!")
                    return true
                }
                if (isGameRunning) {
                    sender.sendMessage("${ChatColor.RED}Нельзя изменять точки во время игры!")
                    return true
                }

                isModeSelection = true
                isChestSelection = false
                isPlayfieldSelection = false

                // Give the player a selection tool
                val selectorTool = ItemStack(Material.BLAZE_ROD)
                val meta = selectorTool.itemMeta
                meta?.setDisplayName("${ChatColor.GOLD}Метка столба")
                selectorTool.itemMeta = meta

                sender.inventory.addItem(selectorTool)
                sender.sendMessage("${ChatColor.GREEN}Режим выбора позиций столбов активирован! Используйте огненную палку для отметки позиции столба.")
                return true
            }
            "assigndone" -> {
                if (sender !is Player) {
                    sender.sendMessage("${ChatColor.RED}Команды должны выполняться игроком!")
                    return true
                }
                isModeSelection = false
                isPlayfieldSelection = false
                // check inventory
                val inventory = sender.inventory
                for (i in 0 until inventory.size) {
                    val item = inventory.getItem(i);
                    if (item?.type == Material.BLAZE_ROD && item.itemMeta?.displayName?.contains("Метка") == true) {
                        item.amount = 0
                    }
                }
                sender.updateInventory()

                sender.sendMessage("${ChatColor.GREEN}Режим выбора отключен.")
                return true
            }
            "assignlobby" -> {
                if (sender !is Player) {
                    sender.sendMessage("${ChatColor.RED}Команды должны выполняться игроком!")
                    return true
                }
                if (isGameRunning) {
                    sender.sendMessage("${ChatColor.RED}Нельзя изменять лобби во время игры!")
                    return true
                }

                lobbyLocation = sender.location.clone()
                sender.sendMessage("${ChatColor.GREEN}Позиция лобби установлена!")
                return true
            }
            "assignplayfield" -> {
                if (sender !is Player) {
                    sender.sendMessage("${ChatColor.RED}Команды должны выполняться игроком!")
                    return true
                }
                if (isGameRunning) {
                    sender.sendMessage("${ChatColor.RED}Нельзя изменять игровое поле во время игры!")
                    return true
                }

                isModeSelection = false
                isChestSelection = false
                isPlayfieldSelection = true
                playfieldCorner1 = null
                playfieldCorner2 = null

                // Give the player a selection tool
                val selectorTool = ItemStack(Material.BLAZE_ROD)
                val meta = selectorTool.itemMeta
                meta?.setDisplayName("${ChatColor.GOLD}Метка игрового поля")
                selectorTool.itemMeta = meta

                sender.sendMessage("${ChatColor.GREEN}Режим выбора игрового поля активирован! Используйте огненную палку для отметки двух противоположных углов поля.")
                sender.inventory.addItem(selectorTool)
                return true
            }
            "assignchest" -> {
                if (sender !is Player) {
                    sender.sendMessage("${ChatColor.RED}Команды должны выполняться игроком!")
                    return true
                }
                if (isGameRunning) {
                    sender.sendMessage("${ChatColor.RED}Нельзя изменять игровое поле во время игры!")
                    return true
                }
                isModeSelection = false
                isChestSelection = true
                isPlayfieldSelection = false
                chestPosition = null
                val selectorTool = ItemStack(Material.BLAZE_ROD)
                val meta = selectorTool.itemMeta
                meta?.setDisplayName("${ChatColor.GOLD}Метка сундука")
                selectorTool.itemMeta = meta
                sender.sendMessage("${ChatColor.GREEN}Режим выбора сундука активирован! Используйте огненную палку для отметки блока, вместо которого должен стоять сундук")
                sender.inventory.addItem(selectorTool)
                return true
            }
            "rmassigndat" -> {
                // Удаление всех сохраненных данных
                columnPositions.clear()
                lobbyLocation = null
                playfieldCorner1 = null
                playfieldCorner2 = null
                worldBorderCenter = null
                chestPosition = null

                // Удаление файлов с данными
                File(dataFolder, "columns.dat").delete()
                File(dataFolder, "lobby.dat").delete()
                File(dataFolder, "playfield.dat").delete()
                File(dataFolder, "chest.dat").delete()

                sender.sendMessage("${ChatColor.GREEN}Все сохраненные настройки удалены!")
                return true
            }
        }

        return false
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item ?: return

        if (item.type == Material.BLAZE_ROD && item.itemMeta?.displayName?.contains("Метка") == true) {
            event.isCancelled = true

            if (isModeSelection) {
                val clickedBlock = event.clickedBlock ?: return
                val location = clickedBlock.location.clone().add(0.5, 1.0, 0.5)

                columnPositions.add(location)
                player.sendMessage("${ChatColor.GREEN}Позиция столба #${columnPositions.size} добавлена: ${location.blockX}, ${location.blockY}, ${location.blockZ}")
            } else if (isPlayfieldSelection) {
                val clickedBlock = event.clickedBlock ?: return
                val location = clickedBlock.location.clone()

                if (playfieldCorner1 == null) {
                    playfieldCorner1 = location
                    player.sendMessage("${ChatColor.GREEN}Первый угол игрового поля установлен: ${location.blockX}, ${location.blockY}, ${location.blockZ}")
                } else if (playfieldCorner2 == null) {
                    playfieldCorner2 = location
                    player.sendMessage("${ChatColor.GREEN}Второй угол игрового поля установлен: ${location.blockX}, ${location.blockY}, ${location.blockZ}")

                    // Расширяем границы на 30 блоков в каждую сторону
                    val corner1 = playfieldCorner1!!
                    val corner2 = playfieldCorner2!!

                    if (corner1.world != corner2.world) {
                        player.sendMessage("${ChatColor.RED}Углы должны быть в одном мире!")
                        return
                    }

                    // Расширяем границы на 30 блоков в каждую сторону
                    val minX = min(corner1.x, corner2.x) - 30
                    val minY = min(corner1.y, corner2.y) - 30
                    val minZ = min(corner1.z, corner2.z) - 30

                    val maxX = max(corner1.x, corner2.x) + 30
                    val maxY = 319.0
                    val maxZ = max(corner1.z, corner2.z) + 30

                    // Обновляем координаты углов с учетом расширения
                    playfieldCorner1 = Location(corner1.world, minX, minY, minZ)
                    playfieldCorner2 = Location(corner1.world, maxX, maxY, maxZ)

                    // Рассчитываем центр игрового поля
                    val centerX = (minX + maxX) / 2
                    val centerZ = (minZ + maxZ) / 2
                    worldBorderCenter = Location(corner1.world, centerX, 0.0, centerZ)

                    player.sendMessage("${ChatColor.GREEN}Игровое поле установлено с расширением +30 блоков! Центр: $centerX, $centerZ")
                    isPlayfieldSelection = false
                    item.amount = 0
                }
            } else if (isChestSelection) {
                val clickedBlock = event.clickedBlock;
                val location = clickedBlock?.location?.clone()?.add(-0.0,0.0,-0.0)
                chestPosition = location;
                player.sendMessage("${ChatColor.GREEN}Отмечен сундук на ${location?.x} ${location?.y} ${location?.z}")
                item.amount = 0
            }
        }
        player.updateInventory()
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        if (!isGameRunning) return

        val player = event.entity
        if (playersInGame.contains(player)) {
            // Перевод игрока в режим наблюдателя после смерти
            Bukkit.getScheduler().runTaskLater(this, Runnable {
                player.gameMode = GameMode.SPECTATOR
                player.sendMessage("${ChatColor.RED}Вы выбыли из игры!")
                playersInGame.remove(player)

                // Обновляем Scoreboard
                updateScoreboard()
            }, 1L)
        }
    }

    @EventHandler
    fun onPlayerDisconnect(event: PlayerQuitEvent) {
        if (!isGameRunning) return
        val player = event.player;
        Bukkit.getScheduler().runTaskLater(this, Runnable {
            playersInGame.remove(player);
        }, 1L)
    }

    @EventHandler
    fun onPlayerGameModeChange(event: PlayerGameModeChangeEvent) {
        if (!isGameRunning) return

        // Check if all players are in spectator mode
        Bukkit.getScheduler().runTaskLater(this, Runnable {
            checkGameStatus()
            playersInGame.remove(event.player)
            updateScoreboard()
        }, 1L)
    }

    private fun startGame(interval: Int, shrinkTimeMinutes: Int) {
        if (columnPositions.isEmpty()) {
            Bukkit.broadcastMessage("${ChatColor.RED}Не заданы позиции столбов! Используйте /assigncols для настройки.")
            return
        }

        if (lobbyLocation == null) {
            Bukkit.broadcastMessage("${ChatColor.RED}Не задано лобби! Используйте /assignlobby для настройки.")
            return
        }

        // Check if we have a playfield defined
        if (worldBorderCenter == null || playfieldCorner1 == null || playfieldCorner2 == null) {
            Bukkit.broadcastMessage("${ChatColor.RED}Не задано игровое поле! Используйте /assignplayfield для настройки.")
            return
        }

        isGameRunning = true
        currentItemInterval = interval
        itemCountdownTicks = interval * 20
        itemCountdownSeconds = interval

        // Set all online players to survival mode
        Bukkit.getOnlinePlayers().forEach { player ->
            player.gameMode = GameMode.SURVIVAL
        }

        // Now add all players to the game (since they're all in survival now)
        playersInGame = Bukkit.getOnlinePlayers().toMutableSet()

        // Set time to day and clear weather
        val world = worldBorderCenter!!.world
        world.time = 1000 // Утро
        world.setStorm(false)
        world.isThundering = false
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false)

        // Save current world border state
        val worldBorder = world.worldBorder
        originalWorldBorderSize = worldBorder.size
        worldBorder.center = worldBorderCenter as Location

        // Calculate size based on playfield with expanded boundaries
        val corner1 = playfieldCorner1!!
        val corner2 = playfieldCorner2!!

        val sizeX = Math.abs(corner1.x - corner2.x)
        val sizeZ = Math.abs(corner1.z - corner2.z)
        val borderSize = Math.sqrt(sizeX * sizeX + sizeZ * sizeZ) / 2 + 30 // Радиус + 30 блоков

        worldBorder.size = borderSize // Диаметр границы мира

        // Save block states in the playfield
        saveBlockStates()

        // Save entities in the playfield for later removal
        saveEntitiesInPlayfield()

        // Randomly assign players to columns
        distributePlayersToColumns()

        // Convert minutes to seconds for the world border shrink
        val shrinkTimeSeconds = shrinkTimeMinutes * 60L

        // Start shrinking world border to the center (finalSize = 0)

        // Break out of this is the round is ended.

        worldBorderShrinkTask = Bukkit.getScheduler().runTaskLater(this, Runnable {
            worldBorder.setSize(0.0, shrinkTimeSeconds)
            killAllTask = Bukkit.getScheduler().runTaskLater(this, Runnable {
                killPlayersAliveIn10Secs();
            }, shrinkTimeSeconds * 20L)
        }, 0)


        // Инициализируем и настраиваем Scoreboard
        setupScoreboard()

        // Инициализируем и настраиваем BossBar
        setupBossBar()

        // Start giving random items
        gameTask = Bukkit.getScheduler().runTaskTimer(this, Runnable {
            if (isGameRunning) {
                giveRandomItems()
                checkGameStatus()

                // Reset countdown after giving items
                itemCountdownTicks = currentItemInterval * 20
                itemCountdownSeconds = currentItemInterval
                updateBossBar()
            }
        }, interval.toLong() * 20L, interval.toLong() * 20L)

        // Start BossBar countdown with smooth updates (every tick - 1/20 of a second)
        bossBarTask = Bukkit.getScheduler().runTaskTimer(this, Runnable {
            if (isGameRunning && itemCountdownTicks > 0) {
                itemCountdownTicks--

                // Update the seconds counter every 20 ticks
                if (itemCountdownTicks % 20 == 0) {
                    itemCountdownSeconds = itemCountdownTicks / 20
                }

                updateBossBar()
            }
        }, 1L, 1L) // Every tick (1/20 of a second)

        Bukkit.broadcastMessage("${ChatColor.GREEN}Игра Столбы началась! Выдача предметов каждые $interval секунд.")
        Bukkit.broadcastMessage("${ChatColor.YELLOW}Граница мира будет сужаться в течение $shrinkTimeMinutes минут!")
        healAllPlayers(true);
        applyMaxSlowToAll();
        healAllPlayers(false);
        setupChest()

    }


    private fun killPlayersAliveIn10Secs() {
        Bukkit.broadcastMessage("${ChatColor.RED}Зона сжалась! Все живые игроки умрут через 10 секунд!")

        Bukkit.getScheduler().runTaskLater(this, Runnable {
            for (player in playersInGame) {
                player.damage(10000.0);
                player.health = 0.0;
            }
        }, 10L * 20L)
    }

    private fun setupScoreboard() {
        // Create a new scoreboard
        val scoreboardManager = Bukkit.getScoreboardManager()
        gameScoreboard = scoreboardManager?.newScoreboard
        val objective = gameScoreboard?.registerNewObjective("players", "dummy", "${ChatColor.GOLD}${ChatColor.BOLD}Игроки")
        objective?.displaySlot = DisplaySlot.SIDEBAR

        // Initial update of the scoreboard
        updateScoreboard()

        // Assign scoreboard to all players
        for (player in Bukkit.getOnlinePlayers()) {
            player.scoreboard = gameScoreboard!!
        }
    }

    private fun updateScoreboard() {
        if (gameScoreboard == null || !isGameRunning) return

        // Get objective
        val objective = gameScoreboard?.getObjective("players") ?: return

        // Clear existing scores
        for (entry in gameScoreboard?.entries ?: emptySet()) {
            gameScoreboard?.resetScores(entry)
        }

        // Add active players to scoreboard
        var score = playersInGame.count();

        for (player in playersInGame) {
                val entryName = "${ChatColor.GREEN}${player.name}"
                objective.getScore(entryName).score = score
                score--
        }

        // If there are no active players, show a message
        if (score == playersInGame.count { it.gameMode != GameMode.SPECTATOR }) {
            objective.getScore("${ChatColor.RED}Нет активных игроков").score = 0
        }
    }

    private fun setupBossBar() {
        // Create boss bar
        itemBossBar = Bukkit.createBossBar(
            "${ChatColor.GOLD}До следующего предмета: ${ChatColor.WHITE}$itemCountdownSeconds сек.",
            BarColor.YELLOW,
            BarStyle.SOLID
        )

        // Add all players to boss bar
        for (player in Bukkit.getOnlinePlayers()) {
            itemBossBar?.addPlayer(player)
        }
    }

    private fun updateBossBar() {
        // Don't update if bossbar isn't initialized or game isn't running
        if (itemBossBar == null || !isGameRunning) return

        // Calculate seconds with one decimal place for display
        val secondsWithDecimal = itemCountdownTicks / 20.0
        val displaySeconds = if (secondsWithDecimal < 10) {
            String.format("%.1f", secondsWithDecimal)
        } else {
            secondsWithDecimal.toInt().toString()
        }

        // Update title
        itemBossBar?.setTitle("${ChatColor.GOLD}До следующего предмета: ${ChatColor.WHITE}$displaySeconds сек.")

        // Update progress (from 1.0 to 0.0 as time progresses)
        val maxTicks = currentItemInterval * 20
        val progress = itemCountdownTicks.toDouble() / maxTicks.toDouble()
        itemBossBar?.progress = progress.coerceIn(0.0, 1.0)

        // Change color based on time remaining
        when {
            progress > 0.67 -> itemBossBar?.color = BarColor.GREEN
            progress > 0.33 -> itemBossBar?.color = BarColor.YELLOW
            else -> itemBossBar?.color = BarColor.RED
        }
    }

    private fun applyMaxSlowToAll() {
        Bukkit.getOnlinePlayers().forEach { player ->
            // Применяем эффект замедления передвижения (Slowness)
            player.addPotionEffect(
                PotionEffect(
                    PotionEffectType.SLOWNESS,
                    40,        // Длительность в тиках (2 секунды = 40 тиков)
                    127,       // Максимальная сила эффекта (127 - самый высокий уровень)
                    false,     // Не скрывать частицы
                    true,      // Показывать иконку
                    true       // Показывать частицы
                )
            )

            // Добавляем эффект слепоты (Blindness)
            player.addPotionEffect(
                PotionEffect(
                    PotionEffectType.BLINDNESS,
                    40,        // Длительность в тиках (2 секунды = 40 тиков)
                    127,       // Максимальная сила эффекта
                    false,     // Не скрывать частицы
                    true,      // Показывать иконку
                    true       // Показывать частицы
                )
            )
        }
    }

    private fun endGame() {
        if (!isGameRunning) return

        playersInGame = Bukkit.getOnlinePlayers().toMutableSet()

        isGameRunning = false
        gameTask?.cancel()
        gameTask = null

        // Cancel bossbar task
        bossBarTask?.cancel()
        bossBarTask = null

        // Remove bossbar
        itemBossBar?.removeAll()
        itemBossBar = null
        // Cancel border task
        worldBorderShrinkTask?.cancel();
        killAllTask?.cancel();

        // Reset world border
        val world = worldBorderCenter?.world ?: return
        val worldBorder = world.worldBorder
        worldBorder.size = originalWorldBorderSize

        // Move world border center far away (5000 blocks)
        worldBorder.center = Location(world, 5000.0, 0.0, 5000.0)

        // Restore block states
        restoreBlockStates()

        // Remove spawned entities
        removeEntities()
        killAllEntities()

        // Reset players
        for (player in playersInGame) {
            player.gameMode = GameMode.SURVIVAL
            player.inventory.clear() // Очистка инвентаря

            // Teleport to lobby if it exists
            lobbyLocation?.let { player.teleport(it) }

            // Reset player's scoreboard to default server scoreboard
            val defaultScoreboard = Bukkit.getScoreboardManager()?.mainScoreboard
            if (defaultScoreboard != null) {
                player.scoreboard = defaultScoreboard
            }
        }

        playersInGame.clear()


        // Re-enable weather cycle
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, true)

        Bukkit.broadcastMessage("${ChatColor.RED}Игра Столбы завершена!")
    }

    private fun distributePlayersToColumns() {
        playersInGame.clear()

        // All players are already in survival mode at this point
        val allPlayers = Bukkit.getOnlinePlayers().toList()
        val shuffledColumns = columnPositions.shuffled()

        for ((index, player) in allPlayers.withIndex()) {
            if (index < shuffledColumns.size) {
                // Assign this player to a column
                val columnPos = shuffledColumns[index]
                player.teleport(columnPos)
                player.inventory.clear()
                playersInGame.add(player)
                player.sendMessage("${ChatColor.GREEN}Вы играете на столбе #${columnPositions.indexOf(columnPos) + 1}!")
            } else {
                // If we run out of columns, put extra players in spectator mode
                player.teleport(shuffledColumns.first())
                player.gameMode = GameMode.SPECTATOR
                playersInGame.remove(player)
                player.sendMessage("${ChatColor.YELLOW}Вы наблюдаете за игрой (не хватило столбов)!")
            }
        }

        updateScoreboard()
    }
    private fun giveRandomItems() {
        if (playersInGame.isEmpty()) return

        // Получаем массив всех материалов и фильтруем только те, которые можно добавить в инвентарь
        val availableMaterials = Material.values().filter {
            it.isItem && it != Material.AIR
        }

        for (player in playersInGame) {
            if (player.gameMode == GameMode.SPECTATOR) continue

            // Выбираем случайный материал из отфильтрованных
            val randomMaterial = availableMaterials.random()

            // Создаем ItemStack из 1 штуки этого материала
            val item = ItemStack(randomMaterial, 1)

            // Выдаем предмет игроку
            player.inventory.addItem(item)
            player.sendMessage("${ChatColor.YELLOW}Вы получили: ${ChatColor.WHITE}${randomMaterial.name.lowercase().replace('_', ' ')}")
        }

//        Bukkit.broadcastMessage("${ChatColor.GOLD}Новые предметы выданы!")
    }

    private fun checkGameStatus() {
        // Count active players
        val activePlayers = playersInGame.count()

        // If only one or zero players left, end the game
        if (activePlayers <= 1) {
            // Find the winner
            val winner = playersInGame.find { it.gameMode != GameMode.SPECTATOR }

            if (winner != null) {
                Bukkit.broadcastMessage("${ChatColor.GOLD}${ChatColor.BOLD}Игрок ${winner.name} победил в игре Столбы!")
            } else {
                Bukkit.broadcastMessage("${ChatColor.GOLD}${ChatColor.BOLD}Игра Столбы завершена без победителя!")
            }

            endGame()
        }
    }

    private fun healAllPlayers(clear: Boolean) {
        Bukkit.getOnlinePlayers().forEach { player ->
            // Установка максимального здоровья
            player.health = player.maxHealth
            // Установка максимального голода
            player.foodLevel = 20
            // Установка максимальной насыщенности
            player.saturation = 20f
            // Удаление всех эффектов зелий
            if (clear) {
                player.activePotionEffects.forEach { effect ->
                    player.removePotionEffect(effect.type)
                }
            }
            // Тушение огня, если игрок горит
            player.fireTicks = 0

            player.sendMessage("§aВы были вылечены и все эффекты были удалены!")
        }
    }

    private fun killAllEntities(): Int {
        var count = 0

        Bukkit.getWorlds().forEach { world ->
            world.entities.forEach { entity ->
                // Проверяем, что сущность не является игроком
                if (entity !is Player) {
                    entity.remove()
                    count++
                }
            }
        }

        return count
    }

    private fun saveBlockStates() {
        blockStates.clear()
        blockInventories.clear()

        val corner1 = playfieldCorner1 ?: return
        val corner2 = playfieldCorner2 ?: return

        val world = corner1.world

        val minX = min(corner1.blockX, corner2.blockX)
        val minY = min(corner1.blockY, corner2.blockY)
        val minZ = min(corner1.blockZ, corner2.blockZ)

        val maxX = max(corner1.blockX, corner2.blockX)
        val maxY = 319
        val maxZ = max(corner1.blockZ, corner2.blockZ)

        // Save block states in the playfield
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val loc = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
                    val block = loc.block
                    blockStates[loc] = BlockState(block.type)
                    if (block.state is Container && block.state !is Chest) {
                        val container = block.state as Container
                        val inventory = container.inventory.contents.map {it?.clone()}
                        blockInventories[loc] = inventory.toList()
                    }
                }
            }
        }
    }

    private fun restoreBlockStates() {
        for ((loc, state) in blockStates) {
            loc.block.type = state.material
            if ( loc.block.state is Container ) {
                val containerBlock = loc.block.state as Container
                val savedInventory = blockInventories[loc]
                savedInventory?.forEachIndexed { index, itemStack ->
                    containerBlock.inventory.setItem(index, itemStack?.clone())
                }
            }
        }
        blockStates.clear()
    }

    private fun saveEntitiesInPlayfield() {
        entitiesToRemove.clear()

        val corner1 = playfieldCorner1 ?: return
        val corner2 = playfieldCorner2 ?: return

        val world = corner1.world

        val minX = min(corner1.blockX, corner2.blockX)
        val minY = min(corner1.blockY, corner2.blockY)
        val minZ = min(corner1.blockZ, corner2.blockZ)

        val maxX = max(corner1.blockX, corner2.blockX)
        val maxY = 319
        val maxZ = max(corner1.blockZ, corner2.blockZ)

        // Save entities in the playfield
        for (entity in world.entities) {
            if (entity.type != EntityType.PLAYER) {
                val loc = entity.location
                if (loc.blockX in minX..maxX &&
                    loc.blockY in minY..maxY &&
                    loc.blockZ in minZ..maxZ) {
                    entitiesToRemove.add(entity)
                }
            }
        }
    }

    private fun removeEntities() {
        for (entity in entitiesToRemove) {
            entity.remove()
        }
        entitiesToRemove.clear()
    }

    private val chestLoot = listOf(
        ItemStack(Material.WATER_BUCKET),
        ItemStack(Material.FISHING_ROD),
        ItemStack(Material.FLINT_AND_STEEL),
        ItemStack(Material.NETHERITE_HOE),
        ItemStack(Material.WOODEN_PICKAXE, 4),
        ItemStack(Material.NAME_TAG),
        ItemStack(Material.SADDLE),
        ItemStack(Material.COMPASS),
        ItemStack(Material.CLOCK),
        ItemStack(Material.MUSIC_DISC_CAT),
        ItemStack(Material.MUSIC_DISC_BLOCKS),
        ItemStack(Material.SLIME_BALL, 3),
        ItemStack(Material.BONE, 8),
        ItemStack(Material.STRING, 6),
        ItemStack(Material.GLASS_BOTTLE, 3),
        ItemStack(Material.HONEY_BOTTLE),
        ItemStack(Material.FEATHER, 4),
        ItemStack(Material.FLOWER_POT),
        ItemStack(Material.ITEM_FRAME),
        ItemStack(Material.PAINTING),
        ItemStack(Material.ELYTRA),
        ItemStack(Material.SNOWBALL, 16),
        ItemStack(Material.BOOK),
        ItemStack(Material.TOTEM_OF_UNDYING),
        ItemStack(Material.NETHERITE_SHOVEL),
        ItemStack(Material.ENDER_EYE),
        ItemStack(Material.CHAIN, 5),
        ItemStack(Material.LADDER, 3),
        ItemStack(Material.SCAFFOLDING, 6),
        ItemStack(Material.SHEARS),
        ItemStack(Material.SPYGLASS),
        ItemStack(Material.CAT_SPAWN_EGG),
        ItemStack(Material.WIND_CHARGE, 5)
    )


    private fun loadData() {
        val columnsFile = File(dataFolder, "columns.dat")
        val lobbyFile = File(dataFolder, "lobby.dat")
        val playfieldFile = File(dataFolder, "playfield.dat")
        val chestFile = File(dataFolder, "chest.dat")

        if (columnsFile.exists()) {
            columnsFile.readLines().forEach { line ->
                try {
                    val parts = line.split(",")
                    val world = Bukkit.getWorld(parts[0]) ?: return@forEach
                    val x = parts[1].toDouble()
                    val y = parts[2].toDouble()
                    val z = parts[3].toDouble()
                    columnPositions.add(Location(world, x, y, z))
                } catch (e: Exception) {
                    logger.warning("Error loading column position: $line")
                }
            }
        }

        if (lobbyFile.exists()) {
            try {
                val parts = lobbyFile.readText().split(",")
                val world = Bukkit.getWorld(parts[0]) ?: return
                val x = parts[1].toDouble()
                val y = parts[2].toDouble()
                val z = parts[3].toDouble()
                val yaw = parts[4].toFloat()
                val pitch = parts[5].toFloat()
                lobbyLocation = Location(world, x, y, z, yaw, pitch)
            } catch (e: Exception) {
                logger.warning("Error loading lobby location")
            }
        }

        if (playfieldFile.exists()) {
            try {
                val lines = playfieldFile.readLines()

                // Load corner 1
                if (lines.size > 0) {
                    val parts = lines[0].split(",")
                    val world = Bukkit.getWorld(parts[0]) ?: return
                    val x = parts[1].toDouble()
                    val y = parts[2].toDouble()
                    val z = parts[3].toDouble()
                    playfieldCorner1 = Location(world, x, y, z)
                }

                // Load corner 2
                if (lines.size > 1) {
                    val parts = lines[1].split(",")
                    val world = Bukkit.getWorld(parts[0]) ?: return
                    val x = parts[1].toDouble()
                    val y = parts[2].toDouble()
                    val z = parts[3].toDouble()
                    playfieldCorner2 = Location(world, x, y, z)
                }

                // Calculate center if both corners are loaded
                if (playfieldCorner1 != null && playfieldCorner2 != null) {
                    val corner1 = playfieldCorner1!!
                    val corner2 = playfieldCorner2!!

                    if (corner1.world == corner2.world) {
                        val centerX = (corner1.x + corner2.x) / 2
                        val centerZ = (corner1.z + corner2.z) / 2
                        worldBorderCenter = Location(corner1.world, centerX, 0.0, centerZ)
                    }
                }
            } catch (e: Exception) {
                logger.warning("Error loading playfield data")
            }
        }
        if (chestFile.exists()) {
            chestFile.readLines().forEach {line ->
                try {
                    val parts = line.split(",");
                    val world = Bukkit.getWorld(parts[0]) ?: return@forEach
                    val x = parts[1].toDouble()
                    val y = parts[2].toDouble()
                    val z = parts[3].toDouble()
                    chestPosition = Location(world,x,y,z);
                }
                catch (e: Exception) {
                    logger.warning("Error chest location: $line")
                }
            }
        }
    }

    private fun saveData() {
        val columnsFile = File(dataFolder, "columns.dat")
        val lobbyFile = File(dataFolder, "lobby.dat")
        val playfieldFile = File(dataFolder, "playfield.dat")
        val chestFile = File(dataFolder, "chest.dat")

        // Save column positions
        columnsFile.printWriter().use { writer ->
            columnPositions.forEach { loc ->
                writer.println("${loc.world.name},${loc.x},${loc.y},${loc.z}")
            }
        }

        // Save lobby location
        lobbyLocation?.let { loc ->
            lobbyFile.printWriter().use { writer ->
                writer.print("${loc.world.name},${loc.x},${loc.y},${loc.z},${loc.yaw},${loc.pitch}")
            }
        }

        chestPosition?.let { loc ->
            chestFile.printWriter().use {writer ->
                writer.print("${loc.world.name},${loc.x},${loc.y},${loc.z}")
            }
        }

        // Save playfield corners
        if (playfieldCorner1 != null || playfieldCorner2 != null) {
            playfieldFile.printWriter().use { writer ->
                playfieldCorner1?.let { loc ->
                    writer.println("${loc.world.name},${loc.x},${loc.y},${loc.z}")
                }
                playfieldCorner2?.let { loc ->
                    writer.println("${loc.world.name},${loc.x},${loc.y},${loc.z}")
                }
            }
        }
    }
}