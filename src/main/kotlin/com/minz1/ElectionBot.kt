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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.dao.id.EntityID
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

    private suspend fun getGeneralVotePreferenceWithCountAsync(preference: Int): Deferred<Map<String, Long>> {
        val candidateId = when (preference) {
            1 -> GeneralVotes.firstCandidateId
            2 -> GeneralVotes.secondCandidateId
            3 -> GeneralVotes.thirdCandidateId
            else -> error("Preference must be in 1 to 3")
        }

        return suspendedTransactionAsync(Dispatchers.IO) {
            addLogger(StdOutSqlLogger)
            GeneralVotes.innerJoin(Users, { candidateId }, {Users.id})
                    .slice(Users.discordId, Users.discordId.count())
                    .selectAll()
                    .groupBy(Users.discordId)
                    .orderBy(Users.discordId.count(), SortOrder.DESC)
                    .map { it[Users.discordId] to it[Users.discordId.count()] }
                    .toMap()
        }
    }

    private suspend fun getTopNomineesByPartyAsync(party: Int): Deferred<Map<String, Long>> {
        return suspendedTransactionAsync(Dispatchers.IO) {
            addLogger(StdOutSqlLogger)
            Nominations.innerJoin(Users, {nomineeId}, {Users.id})
                .slice(Users.discordId, Users.discordId.count(), Users.party)
                .select { Users.party eq party }
                .groupBy(Users.discordId)
                .orderBy(Users.discordId.count(), SortOrder.DESC)
                .map { it[Users.discordId] to it[Users.discordId.count()] }
                .toMap()
        }
    }

    private suspend fun getRNomineesFromNominees(): List<List<String>> {
        val allPartyNomineeLists = listOf(ArrayList<String>(getTopNomineesByPartyAsync(1).await().keys),
            ArrayList<String>(getTopNomineesByPartyAsync(2).await().keys),
            ArrayList<String>(getTopNomineesByPartyAsync(3).await().keys))

        for (topNominees in allPartyNomineeLists) {
            val size = topNominees.size
            if (size > 2) {
                topNominees.dropLast(size - 2)
            }
        }

        return allPartyNomineeLists.map { it.toList() }
    }

    private suspend fun getRNomineesWithCountAsync(party: Int): Deferred<Map<String, Long>> {
        return suspendedTransactionAsync(Dispatchers.IO) {
            addLogger(StdOutSqlLogger)
            RunoffNominations.innerJoin(Users, {runoffNomineeId}, {Users.id})
                .slice(Users.discordId, Users.discordId.count(), Users.party)
                .select { Users.party eq party }
                .groupBy(Users.discordId)
                .orderBy(Users.discordId.count(), SortOrder.DESC)
                .map { it[Users.discordId] to it[Users.discordId.count()] }
                .toMap()
        }
    }

    private suspend fun getFinalNominees(): List<String> {
        return listOf(ArrayList<String>(getRNomineesWithCountAsync(1).await().keys),
        ArrayList<String>(getRNomineesWithCountAsync(2).await().keys),
        ArrayList<String>(getRNomineesWithCountAsync(3).await().keys)).map {
            if (it.isNotEmpty()) {
                it[0]
            } else {
                ""
            }
        }
    }

    private suspend fun getDropoutsAsync(): Deferred<List<String>> {
        return suspendedTransactionAsync(Dispatchers.IO) {
            addLogger(StdOutSqlLogger)
            Dropouts.innerJoin(Users, { dropoutUserId }, {Users.id})
                .slice(Users.discordId)
                .selectAll()
                .map { it[Users.discordId] }
        }
    }

    private suspend fun getGeneralVotersAsync(): Deferred<List<String>> {
        return suspendedTransactionAsync(Dispatchers.IO) {
            addLogger(StdOutSqlLogger)
            GeneralVotes.innerJoin(Users, {voterId}, {Users.id})
                    .slice(Users.discordId)
                    .selectAll()
                    .map { it[Users.discordId] }
        }
    }

    private suspend fun getNominatorsAsync(): Deferred<List<String>> {
        return suspendedTransactionAsync(Dispatchers.IO) {
            addLogger(StdOutSqlLogger)
            Nominations.innerJoin(Users, { nominatorId }, {Users.id})
                .slice(Users.discordId)
                .selectAll()
                .map { it[Users.discordId] }
        }
    }

    private suspend fun getRNominatorsAsync(): Deferred<List<String>> {
        return suspendedTransactionAsync(Dispatchers.IO) {
            addLogger(StdOutSqlLogger)
            RunoffNominations.innerJoin(Users, { runoffNominatorId }, {Users.id})
                .slice(Users.discordId)
                .selectAll()
                .map { it[Users.discordId] }
        }
    }

    private suspend fun getRegisteredVoterPartiesAsync(): Deferred<Map<String, Int>> {
        return suspendedTransactionAsync(Dispatchers.IO, db = db) {
            addLogger(StdOutSqlLogger)
            Users.slice(Users.discordId, Users.party)
                .selectAll()
                .map { it[Users.discordId] to it[Users.party] }
                .toMap()
        }
    }

    private suspend fun getRegisteredVoterIdsAsync(): Deferred<Map<String, EntityID<Int>>> {
        return suspendedTransactionAsync(Dispatchers.IO) {
            addLogger(StdOutSqlLogger)
            Users.slice(Users.discordId, Users.id)
                .selectAll()
                .map { it[Users.discordId] to it[Users.id] }
                .toMap()
        }
    }

    suspend fun runBot() {
        val roles = ArrayList<Role>()

        for (role in guildClient.getRoles()) {
            if (role.id == roleOne || role.id == roleTwo || role.id == roleThree)
                roles.add(role)
        }

        require(roles.size == 3) {
            "ERROR: must have all 3 roles!"
        }

        require(stage in 1..4) {
            "ERROR: Stage must be 1, 2, 3 or 4!"
        }

        TransactionManager.manager.defaultIsolationLevel = 8 // Connection.TRANSACTION_SERIALIZABLE = 8

        transaction(db) {
            addLogger(StdOutSqlLogger)
            SchemaUtils.create(Users, Nominations, Dropouts, RunoffNominations, GeneralVotes)
        }

        val usageTitle = "Usage information"
        val usageDescription = "CURRENT STAGE: $stage\n\n" +
                "**${cmdPrefix}register**: *(STAGE 1)*  Takes an argument of either 1, 2, or 3\n" +
                "Assigns the calling user to one of the following parties:\n" +
                "1: ${roles[0].name}, 2: ${roles[1].name}, 3: ${roles[2].name}\n\n" +
                "**${cmdPrefix}nominate**: *(STAGE 2)*  Takes an argument of a user mention.\n" +
                "Gives a vote for the first round of nominations to the mentioned user.\n\n" +
                "**${cmdPrefix}rnominate**: *(STAGE 3)*  Takes an argument of a user mention.\n" +
                "Gives a vote for the second round of nominations to the mentioned user.\n\n" +
                "**${cmdPrefix}vote**: *(STAGE 4)*  Takes 3 arguments of user mentions.\n" +
                "Gives a vote for the final round of nominations to the mentioned users.\n" +
                "These are ranked by preference. The first mention being the first preference,\n" +
                "second being the second preference, and third being the third preference.\n\n" +
                "**${cmdPrefix}listings**: *(STAGES 2 to 4)*  Takes an argument of either 1, 2, or 3\n" +
                "Prints a vote report for the current stage, with the party corresponding to one of the following: \n" +
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
                                description = "The registration stage is not active!"
                            }
                            return@command
                        }

                        val authorId = this.author.id

                        val registeredVoterIds = getRegisteredVoterPartiesAsync().await()

                        if (registeredVoterIds.containsKey(authorId)) {
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
                        if (stage !in 2..4) {
                            reply {
                                title = "Error!"
                                description = "The voting stages are not active!"
                            }
                            return@command
                        }

                        val nomineeListings = when(stage) {
                            2 -> getTopNomineesByPartyAsync(num).await()
                            3 -> getRNomineesWithCountAsync(num).await()
                            4 -> getGeneralVotePreferenceWithCountAsync(num).await()
                            else -> emptyMap()
                        }

                        val stringBuilder = StringBuilder()
                        val nomineeIds = nomineeListings.keys.toList()

                        if (nomineeListings.size > 5) {
                            for (i in 0 until 5) {
                                val nomineeId = nomineeIds[i]
                                val nominee = guildClient.getMember(nomineeId)
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

                        val beginningString = when(stage) {
                            2 -> "Stage two nominee"
                            3 -> "Stage three nominee"
                            4 -> "Stage four nominee"
                            else -> "Nominee"
                        }
                        val endString = if (stage == 4) {
                            when(num) {
                                1 -> "first preference nominees"
                                2 -> "second preference nominees"
                                3 -> "third preference nominees"
                                else -> "nominees"
                            }
                        } else {
                            "${roles[num - 1].name} party"
                        }

                        reply {
                            title = "$beginningString listings for the $endString"
                            description = "$stringBuilder"
                        }
                    }
                }

                command("nominate") {
                    if (stage != 2) {
                        reply {
                            title = "Error!"
                            description = "The first nomination stage is not active!"
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

                            val previousNominatorIds = getNominatorsAsync().await()

                            if (nominatorUserId in previousNominatorIds) {
                                reply {
                                    title = "Error!"
                                    description = "You have already voted for someone!"
                                }
                                return@command
                            }

                            val previousDropoutIds = getDropoutsAsync().await()

                            if (nomineeUserId in previousDropoutIds) {
                                reply {
                                    title = "Error!"
                                    description = "${nominee.nickname ?: nominee.user?.username ?: "this person"} has" +
                                            " already dropped out!"
                                }
                                return@command
                            }

                            val registeredVoters = getRegisteredVoterPartiesAsync().await()

                            if (nominatorUserId in registeredVoters.keys && registeredVoters[nominatorUserId] != 0) {
                                if (nomineeUserId in registeredVoters.keys && registeredVoters[nomineeUserId] != 0) {
                                    if (registeredVoters[nominatorUserId] == registeredVoters[nomineeUserId]) {
                                        reply {
                                            title = "Success!"
                                            description = "Your vote for ${nominee.nickname ?: nominee.user?.username ?: "this person"} " +
                                                    "has been recorded."
                                        }

                                        val userDbIds = getRegisteredVoterIdsAsync().await()

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

                command("dropout") {
                    if (stage !in 2..3) {
                        reply {
                            title = "Error!"
                            description = "The nomination stages are not active!"
                        }
                        return@command
                    }

                    val args = this.content.removePrefix("\$dropout ").trim().split(" ")

                    if (args.isNotEmpty()) {
                        try {
                            val dropoutId = this.authorId

                            val nomineeUserId = if (args[0].contains("<@!")) {
                                args[0].removePrefix("<@!").removeSuffix(">")
                            } else {
                                args[0].removePrefix("<@").removeSuffix(">")
                            }

                            val nominee = guildClient.getMember(nomineeUserId)

                            val previousDropoutIds = getDropoutsAsync().await()

                            if (dropoutId in previousDropoutIds) {
                                reply {
                                    title = "Error!"
                                    description = "You have already dropped out!"
                                }
                                return@command
                            }

                            if (nomineeUserId in previousDropoutIds) {
                                reply {
                                    title = "Error!"
                                    description = "${nominee.nickname ?: nominee.user?.username ?: "this person"} has" +
                                            " already dropped out!"
                                }
                                return@command
                            }

                            val registeredVoters = getRegisteredVoterPartiesAsync().await()

                            if (dropoutId in registeredVoters.keys && registeredVoters[dropoutId] != 0) {
                                if (nomineeUserId in registeredVoters.keys && registeredVoters[nomineeUserId] != 0) {
                                    val dropoutParty = registeredVoters[dropoutId]!!
                                    val nomineeParty = registeredVoters[nomineeUserId]!!

                                    if (stage == 3) {
                                        val rnominees = getRNomineesFromNominees()

                                        if (dropoutId !in rnominees[dropoutParty - 1]) {
                                            reply {
                                                title = "Error!"
                                                description = "You are no longer in the race!"
                                            }
                                            return@command
                                        } else if (nomineeUserId !in rnominees[nomineeParty - 1]) {
                                            reply {
                                                title = "Error!"
                                                description = "${nominee.nickname ?: nominee.user?.username ?: "this person"}" +
                                                        " is no longer in the race!"
                                            }
                                            return@command
                                        }
                                    }

                                    if (dropoutParty == nomineeParty) {
                                        reply {
                                            title = "Success!"
                                            description = "You have dropped out, and your votes have been transferred to" +
                                                    " ${nominee.nickname ?: nominee.user?.username ?: "this person"}."
                                        }

                                        val userDbIds = getRegisteredVoterIdsAsync().await()

                                        newSuspendedTransaction(Dispatchers.IO) {
                                            addLogger(StdOutSqlLogger)

                                            if (stage == 2) {
                                                Nominations.update({ Nominations.nomineeId eq userDbIds[dropoutId]!! }) {
                                                    it[nomineeId] = userDbIds[nomineeUserId]!!
                                                }
                                            } else if (stage == 3) {
                                                RunoffNominations.update({ RunoffNominations.runoffNomineeId eq userDbIds[dropoutId]!! }) {
                                                    it[runoffNomineeId] = userDbIds[nomineeUserId]!!
                                                }
                                            }

                                            Dropout.new {
                                                dropoutUserId = userDbIds[dropoutId]!!
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
                                        description = "Your nominee must be in a party to complete the transfer!!"
                                    }
                                }
                            } else {
                                reply {
                                    title = "Error!"
                                    description = "You must be in a party to dropout!"
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

                command("rnominate") {
                    if (stage != 3) {
                        reply {
                            title = "Error!"
                            description = "The second nomination stage is not active!"
                        }
                        return@command
                    }

                    val args = this.content.removePrefix("\$rnominate ").trim().split(" ")

                    if (args.isNotEmpty()) {
                        try {
                            val rnominatorUserId = this.authorId

                            val rnomineeUserId = if (args[0].contains("<@!")) {
                                args[0].removePrefix("<@!").removeSuffix(">")
                            } else {
                                args[0].removePrefix("<@").removeSuffix(">")
                            }

                            val rnominee = guildClient.getMember(rnomineeUserId)

                            val previousRNominatorIds = getRNominatorsAsync().await()

                            if (rnominatorUserId in previousRNominatorIds) {
                                reply {
                                    title = "Error!"
                                    description = "You have already voted for someone!"
                                }
                                return@command
                            }

                            val previousDropoutIds = getDropoutsAsync().await()

                            if (rnomineeUserId in previousDropoutIds) {
                                reply {
                                    title = "Error!"
                                    description = "${rnominee.nickname ?: rnominee.user?.username ?: "this person"} has" +
                                            " already dropped out!"
                                }
                                return@command
                            }

                            val registeredVoters = getRegisteredVoterPartiesAsync().await()

                            if (rnominatorUserId in registeredVoters.keys && registeredVoters[rnominatorUserId] != 0) {
                                if (rnomineeUserId in registeredVoters.keys && registeredVoters[rnomineeUserId] != 0) {
                                    if (registeredVoters[rnominatorUserId] == registeredVoters[rnomineeUserId]) {
                                        val party = registeredVoters[rnominatorUserId]!!
                                        val rnominees = getRNomineesFromNominees()

                                        if (rnomineeUserId !in rnominees[party - 1]) {
                                            reply {
                                                title = "Error!"
                                                description = "${rnominee.nickname ?: rnominee.user?.username ?: "this person"}" +
                                                        " is no longer in the race!"
                                            }
                                            return@command
                                        }

                                        reply {
                                            title = "Success!"
                                            description = "Your vote for ${rnominee.nickname ?: rnominee.user?.username ?: "this person"} " +
                                                    "has been recorded."
                                        }

                                        val userDbIds = getRegisteredVoterIdsAsync().await()

                                        newSuspendedTransaction(Dispatchers.IO) {
                                            addLogger(StdOutSqlLogger)
                                            RunoffNomination.new {
                                                runoffNominatorId = userDbIds[rnominatorUserId]!!
                                                runoffNomineeId = userDbIds[rnomineeUserId]!!
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

                command("vote") {
                    if (stage != 4) {
                        reply {
                            title = "Error!"
                            description = "The final nomination stage is not active!"
                        }
                        return@command
                    }

                    val args = this.content.removePrefix("\$vote ").trim().split(" ")

                    if (args.size == 3) {
                        try {
                            val voterId = this.authorId

                            val previousVoters = getGeneralVotersAsync().await()

                            if (voterId in previousVoters) {
                                reply {
                                    title = "Error!"
                                    description = "You have already submitted your ranked vote!"
                                }
                                return@command
                            }

                            val rankedNomineeIds = args.map {
                                if (it.contains("<@!")) {
                                    it.removePrefix("<@!").removeSuffix(">")
                                } else {
                                    it.removePrefix("<@").removeSuffix(">")
                                }
                            }

                            if (rankedNomineeIds.distinct().size != rankedNomineeIds.size) {
                                reply {
                                    title = "Error!"
                                    description = "You cannot vote for a candidate more than once!"
                                }
                                return@command
                            }

                            val finalNomineeIds = getFinalNominees()

                            for (rankedNomineeId in rankedNomineeIds) {
                                if (rankedNomineeId !in finalNomineeIds) {
                                    reply {
                                        title = "Error!"
                                        description = "One (or more) of your nominees isn't one of the final candidates!"
                                    }
                                    return@command
                                }
                            }

                            val rankedNominees = rankedNomineeIds.map {
                                guildClient.getMember(it)
                            }

                            val registeredVoters = getRegisteredVoterPartiesAsync().await()

                            if (voterId !in registeredVoters.keys) {
                                newSuspendedTransaction(Dispatchers.IO) {
                                    User.new {
                                        discordId = voterId
                                        party = 0
                                    }
                                }
                            }

                            val stringBuilder = StringBuilder()

                            stringBuilder.append("Your vote for these candidates have been recorded:\n\n")
                            var i = 1
                            for (rankedNominee in rankedNominees) {
                                stringBuilder.append("${i++}: ${rankedNominee.nickname ?: rankedNominee.user?.username ?: "Some person"}\n")
                            }

                            reply {
                                title = "Success!"
                                description = "$stringBuilder"
                            }

                            val userDbIds = getRegisteredVoterIdsAsync().await()

                            newSuspendedTransaction(Dispatchers.IO) {
                                addLogger(StdOutSqlLogger)
                                GeneralVote.new {
                                    this.voterId = userDbIds[voterId]!!
                                    firstCandidateId = userDbIds[rankedNomineeIds[0]]!!
                                    secondCandidateId = userDbIds[rankedNomineeIds[1]]!!
                                    thirdCandidateId = userDbIds[rankedNomineeIds[2]]!!
                                }
                            }
                        } catch (de: DiscordException) {
                            reply {
                                title = "Error!"
                                description = "You must invoke this command with a mention of 3 nominees, in order of" +
                                        " preference!"
                            }
                        }
                    } else {
                        reply {
                            title = "Error!"
                            description = "You must invoke this command with a mention of 3 nominees, in order of" +
                                    " preference!"
                        }
                    }
                }
            }
        }
    }
}