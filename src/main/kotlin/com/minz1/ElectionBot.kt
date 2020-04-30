package com.minz1

import com.jessecorbett.diskord.api.exception.DiscordException
import com.jessecorbett.diskord.api.model.Role
import com.natpryce.konfig.*
import com.jessecorbett.diskord.api.rest.client.GuildClient
import com.jessecorbett.diskord.dsl.bot
import com.jessecorbett.diskord.dsl.command
import com.jessecorbett.diskord.dsl.commands
import com.jessecorbett.diskord.util.authorId
import com.natpryce.konfig.Key
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.experimental.suspendedTransactionAsync
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.lang.StringBuilder

class ElectionBot {
    private val BOT_TOKEN = "bot.token"
    private val CMD_PREFIX = "command.prefix"
    private val FILE_NAME = "election.bot.properties"
    private val GUILD_ID = "guild.id"
    private val ROLE_ONE = "role.one"
    private val ROLE_TWO = "role.two"
    private val ROLE_THREE = "role.three"
    private val STAGE = "stage"

    private val config = ConfigurationProperties.systemProperties() overriding
            EnvironmentVariables() overriding
            ConfigurationProperties.fromFile(File(FILE_NAME))

    private val keyCmdPrefix = Key(CMD_PREFIX, stringType)
    private val keyBotToken = Key(BOT_TOKEN, stringType)
    private val keyGuildId = Key(GUILD_ID, stringType)
    private val keyRoleOne = Key(ROLE_ONE, stringType)
    private val keyRoleTwo = Key(ROLE_TWO, stringType)
    private val keyRoleThree = Key(ROLE_THREE, stringType)
    private val keyStage = Key(STAGE, intType)

    private val cmdPrefix = config[keyCmdPrefix]
    private val botToken = config[keyBotToken]
    private val guildId = config[keyGuildId]
    private val roleOne = config[keyRoleOne]
    private val roleTwo = config[keyRoleTwo]
    private val roleThree = config[keyRoleThree]
    private val stage = config[keyStage]

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

        require(stage in 1..3) {
            "ERROR: Stage must be 1, 2, or 3!"
        }

        TransactionManager.manager.defaultIsolationLevel = 8 // Connection.TRANSACTION_SERIALIZABLE = 8

        transaction(db) {
            addLogger(StdOutSqlLogger)
            SchemaUtils.create(Users, Nominations)
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

                for (num in 1..3) {
                    command("register $num") {
                        if (stage != 1) {
                            reply {
                                title = "Error!"
                                description = "The registration period is over!"
                            }
                            return@command
                        }

                        val authorId = this.author.id

                        val registeredVoterIds = suspendedTransactionAsync(Dispatchers.IO, db = db) {
                            addLogger(StdOutSqlLogger)
                            Users.slice(Users.discordId).selectAll().toList().map { it[Users.discordId] }
                        }.await()

                        if (authorId in registeredVoterIds) {
                            reply {
                                title = "No need for any action"
                                description = "You have already registered for a party!"
                            }
                        } else {
                            guildClient.addMemberRole(authorId, roles[num - 1].id)

                            reply {
                                title = "Success!"
                                description = "You've signed up for the ${roles[num - 1].name} party!"
                            }

                            newSuspendedTransaction(Dispatchers.IO) {
                                addLogger(StdOutSqlLogger)
                                User.new {
                                    discordId = authorId
                                    party = num
                                }
                            }
                        }
                    }

                    command("listings $num") {
                        val nomineeListings = suspendedTransactionAsync(Dispatchers.IO) {
                            addLogger(StdOutSqlLogger)
                            Nominations.innerJoin(Users, {Nominations.nomineeId}, {Users.id})
                                    .slice(Users.discordId, Users.discordId.count(), Users.party)
                                    .select { Users.party eq num }
                                    .groupBy(Users.discordId)
                                    .orderBy(Users.discordId.count(), SortOrder.DESC)
                                    .map { it[Users.discordId] to it[Users.discordId.count()] }
                                    .toMap()
                        }.await()

                        val stringBuilder = StringBuilder()
                        val nomineeIds = nomineeListings.keys.toList()

                        if (nomineeListings.size > 5) {
                            for (i in 0 until 5) {
                                val nomineeId = nomineeIds[i]
                                val nominee = guildClient.getMember(nomineeId)
                                println(nomineeId)
                                println(nomineeListings[nomineeId])
                                stringBuilder.append("${nominee.nickname ?: nominee.user?.username?: nomineeId}: " +
                                        "${nomineeListings[nomineeId]}\n")
                            }
                        } else {
                            for (i in 0 until nomineeListings.size) {
                                val nomineeId = nomineeIds[i]
                                val nominee = guildClient.getMember(nomineeId)
                                stringBuilder.append("${nominee.nickname ?: nominee.user?.username?: nomineeId}: " +
                                        "${nomineeListings[nomineeId]}\n")
                            }
                        }

                        reply {
                            title = "Nominee listings for the ${roles[(num - 1)].name} party"
                            description = "$stringBuilder"
                        }
                    }
                }

                command("nominate") {
                    if (stage != 2) {
                        reply {
                            title = "Error!"
                            description = "The nomination period has ended!"
                        }
                        return@command
                    }

                    val args = this.content.removePrefix("\$nominate ").trim().split(" ")

                    if (args.isNotEmpty()) {
                        try {
                            val nominatorUserId = this.authorId

                            val nomineeUserId = if (args[0].contains("<@!")) {
                                args[0].removePrefix("<@!").removeSuffix(">")
                            } else {
                                args[0].removePrefix("<@").removeSuffix(">")
                            }

                            val nominee = guildClient.getMember(nomineeUserId)

                            val previousNominatorIds = suspendedTransactionAsync(Dispatchers.IO) {
                                addLogger(StdOutSqlLogger)
                                Nominations.innerJoin(Users, { nominatorId }, {Users.id})
                                        .slice(Users.discordId)
                                        .selectAll()
                                        .map { it[Users.discordId] }
                            }.await()

                            if (nominatorUserId in previousNominatorIds) {
                                reply {
                                    title = "Error!"
                                    description = "You have already voted for someone!"
                                }
                                return@command
                            }

                            val registeredVoters = suspendedTransactionAsync(Dispatchers.IO) {
                                addLogger(StdOutSqlLogger)
                                Users.slice(Users.discordId, Users.party)
                                        .selectAll()
                                        .map { it[Users.discordId] to it[Users.party] }
                                        .toMap()
                            }.await()

                            if (nominatorUserId in registeredVoters.keys) {
                                if (nomineeUserId in registeredVoters.keys) {
                                    if (registeredVoters[nominatorUserId] == registeredVoters[nomineeUserId]) {
                                        reply {
                                            title = "Success!"
                                            description = "Your vote for ${nominee.nickname ?: nominee.user?.username ?: "this person"} " +
                                                    "has been recorded."
                                        }

                                        val userDbIds = suspendedTransactionAsync(Dispatchers.IO) {
                                            addLogger(StdOutSqlLogger)
                                            Users.slice(Users.discordId, Users.id)
                                                    .selectAll()
                                                    .map { it[Users.discordId] to it[Users.id] }
                                                    .toMap()
                                        }.await()

                                        newSuspendedTransaction(Dispatchers.IO) {
                                            addLogger(StdOutSqlLogger)
                                            Nomination.new {
                                                nominatorId = userDbIds[nominatorUserId]!!
                                                nomineeId = userDbIds[nomineeUserId]!!
                                            }
                                        }
                                    } else {
                                        reply {
                                            title = "Error!"
                                            description = "Your nominee must share the same party as you!"
                                        }
                                    }
                                } else {
                                    reply {
                                        title = "Error!"
                                        description = "Your nominee must be in a party in order to be voted for!"
                                    }
                                }
                            } else {
                                reply {
                                    title = "Error!"
                                    description = "You must be in a party to vote for a nominee!"
                                }
                            }
                        } catch (de: DiscordException) {
                            reply {
                                title = "Error!"
                                description = "You must invoke this command with a mention of your nominee!"
                            }
                        }
                    }
                }
            }
        }
    }
}