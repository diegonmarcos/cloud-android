package com.diegonmarcos.superapp.chat

/**
 * Plain Kotlin DTOs mirroring the Mattermost REST API v4 response
 * shapes for the surfaces the SuperApp's Comms/Mattermost page uses:
 * teams, categories, channels, and per-channel membership (for unread).
 *
 * Only the fields the UI actually reads are kept. The Mattermost
 * objects have ~20 fields each; carrying the full schema here would
 * just bloat the parser. Add fields when a new UI surface needs them.
 */

/** /api/v4/users/login response body. */
data class MattermostUser(
    val id: String,
    val username: String,
    val email: String,
)

/** /api/v4/users/me/teams entry. */
data class MattermostTeam(
    val id: String,
    val displayName: String,
    val name: String,
)

/** /api/v4/users/{user_id}/teams/{team_id}/channels/categories category.
 *  type is "favorites" | "channels" | "direct_messages" | "custom".
 *  channel_ids is the ordered list of channel IDs in this category. */
data class MattermostCategory(
    val id: String,
    val displayName: String,
    val type: String,
    val channelIds: List<String>,
)

/** /api/v4/users/{user_id}/teams/{team_id}/channels entry. */
data class MattermostChannel(
    val id: String,
    val teamId: String,
    /** "O"=public, "P"=private, "D"=DM, "G"=group DM. */
    val type: String,
    /** Display name (empty for DMs; the UI then resolves to the other user's name). */
    val displayName: String,
    val name: String,
    /** Cumulative message count posted to the channel since creation. */
    val totalMsgCount: Long,
)

/** /api/v4/users/{user_id}/teams/{team_id}/channels/members entry —
 *  carries the per-user view state needed to derive unread counts:
 *  unread = channel.totalMsgCount − member.msgCount. */
data class MattermostMember(
    val channelId: String,
    val userId: String,
    /** Number of messages the user has marked read in this channel. */
    val msgCount: Long,
    /** Direct mention count (for badge highlighting). */
    val mentionCount: Long,
)

/** Derived view-model: a single channel row in the UI, with its
 *  resolved unread count + mention count joined from the matching
 *  [MattermostMember]. */
data class ChannelRow(
    val channel: MattermostChannel,
    val unreadCount: Long,
    val mentionCount: Long,
)
