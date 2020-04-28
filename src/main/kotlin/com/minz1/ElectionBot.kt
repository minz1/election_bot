package com.minz1

import com.jessecorbett.diskord.api.model.Role
import com.natpryce.konfig.*
import com.jessecorbett.diskord.api.rest.client.GuildClient
import com.jessecorbett.diskord.dsl.bot
import com.jessecorbett.diskord.dsl.command
import com.jessecorbett.diskord.dsl.commands
import com.natpryce.konfig.Key
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.experimental.suspendedTransactionAsync
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

class ElectionBot {
    private val BOT_TOKEN = "bot.token"
    private val CMD_PREFIX = "command.prefix"
    private val FILE_NAME = "election.bot.properties"
    private val GUILD_ID = "guild.id"
    private val ROLE_ONE = "role.one"
    private val ROLE_TWO = "role.two"
    private val ROLE_THREE = "role.three"

    private val config = ConfigurationProperties.systemProperties() overriding
            EnvironmentVariables() overriding
            ConfigurationProperties.fromFile(File(FILE_NAME))

    private val keyBotToken = Key(BOT_TOKEN, stringType)
    private val keyGuildId = Key(GUILD_ID, stringType)
    private val keyRoleOne = Key(ROLE_ONE, stringType)
    private val keyRoleTwo = Key(ROLE_TWO, stringType)
    private val keyRoleThree = Key(ROLE_THREE, stringType)
    private val keyCmdPrefix = Key(CMD_PREFIX, stringType)

    private val botToken = config[keyBotToken]
    private val guildId = config[keyGuildId]
    private val roleOne = config[keyRoleOne]
    private val roleTwo = config[keyRoleTwo]
    private val roleThree = config[keyRoleThree]
    private val cmdPrefix = config[keyCmdPrefix]

    private val db = Database.connect("jdbc:sqlite:electionbot.db", "org.sqlite.JDBC")
    private val guildClient = GuildClient(botToken, guildId)

    suspend fun runBot() {
        val roles = ArrayList<Role>()

        for (role in guildClient.getRoles()) {
            if (role.id == roleOne || role.id == roleTwo || role.id == roleThree)
                roles.add(role)
        }

        require(roles.size == 3) {
            "ERROR: must have all 3 roles!"
        }

        TransactionManager.manager.defaultIsolationLevel = 8 // Connection.TRANSACTION_SERIALIZABLE = 8

        transaction(db) {
            addLogger(StdOutSqlLogger)
            SchemaUtils.create(Users)
        }

        val usageTitle = "Usage information"
        val usageDescription = "${cmdPrefix}register: Takes an argument of either 1, 2, or 3\n" +
                "Assigns the calling user to one of the following parties:\n" +
                "1: ${roles[0].name}, 2: ${roles[1].name}, 3: ${roles[2].name}"

        bot(botToken) {
            commands(cmdPrefix) {
                command("help") {
                    reply {
                        title = usageTitle
                        description = usageDescription
                    }
                }

                command("register") {
                    val message = this.content.trim()
                    val userId = this.author.id

                    if (message.length == 11 && message[10].isDigit()) {
                        val num = message[10].toInt() - '0'.toInt() // since toInt() for a Char returns an ASCII code, subtract the code for 0 to get our number

                        val query = suspendedTransactionAsync(Dispatchers.IO, db = db) {
                            addLogger(StdOutSqlLogger)
                            Users.slice(Users.discordId).selectAll().toList()
                        }.await()
                        val userIds = query.map { it[Users.discordId] }

                        if (userId in userIds) {
                            reply {
                                title = "No need for any action"
                                description = "You have already registered for a party!"
                            }
                            return@command
                        }

                        if (num in 1..3) {
                            guildClient.addMemberRole(userId, roles[num - 1].id)

                            reply {
                                title = "Success!"
                                description = "You've signed up for the ${roles[num - 1].name} party!"
                            }
                        } else {
                            reply {
                                title = usageTitle
                                description = usageDescription
                            }
                            return@command
                        }

                        newSuspendedTransaction(Dispatchers.IO) {
                            addLogger(StdOutSqlLogger)
                            User.new {
                                discordId = userId
                                party = num
                            }
                        }
                    } else {
                        reply {
                            title = usageTitle
                            description = usageDescription
                        }
                    }
                }
            }
        }
    }
}