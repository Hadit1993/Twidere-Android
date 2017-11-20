/*
 *             Twidere - Twitter client for Android
 *
 *  Copyright (C) 2012-2017 Mariotaku Lee <mariotaku.lee@gmail.com>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mariotaku.twidere.promise

import android.app.Application
import android.content.SharedPreferences
import com.squareup.otto.Bus
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.then
import org.mariotaku.kpreferences.get
import org.mariotaku.microblog.library.MicroBlog
import org.mariotaku.microblog.library.MicroBlogException
import org.mariotaku.microblog.library.mastodon.Mastodon
import org.mariotaku.sqliteqb.library.Expression
import org.mariotaku.twidere.R
import org.mariotaku.twidere.annotation.AccountType
import org.mariotaku.twidere.constant.nameFirstKey
import org.mariotaku.twidere.dagger.component.PromisesComponent
import org.mariotaku.twidere.extension.get
import org.mariotaku.twidere.extension.model.api.mastodon.toParcelable
import org.mariotaku.twidere.extension.model.api.toParcelable
import org.mariotaku.twidere.extension.model.newMicroBlogInstance
import org.mariotaku.twidere.model.ObjectId
import org.mariotaku.twidere.model.ParcelableUser
import org.mariotaku.twidere.model.UserKey
import org.mariotaku.twidere.model.event.FriendshipTaskEvent
import org.mariotaku.twidere.provider.TwidereDataStore.Statuses
import org.mariotaku.twidere.util.UserColorNameManager
import org.mariotaku.twidere.util.Utils
import org.mariotaku.twidere.util.lang.ApplicationContextSingletonHolder
import javax.inject.Inject

class FriendshipPromises private constructor(val application: Application) {

    private val profileImageSize: String = application.getString(R.string.profile_image_size)

    @Inject
    lateinit var preferences: SharedPreferences
    @Inject
    lateinit var manager: UserColorNameManager
    @Inject
    lateinit var bus: Bus

    init {
        PromisesComponent.get(application).inject(this)
    }

    fun accept(accountKey: UserKey, userKey: UserKey): Promise<ParcelableUser, Exception>
            = notifyCreatePromise(bus, FriendshipTaskEvent.Action.ACCEPT, accountKey, userKey)
            .thenGetAccount(application, accountKey).then { details ->
        when (details.type) {
            AccountType.FANFOU -> {
                val fanfou = details.newMicroBlogInstance(application, MicroBlog::class.java)
                return@then fanfou.acceptFanfouFriendship(userKey.id).toParcelable(details,
                        profileImageSize = profileImageSize)
            }
            AccountType.MASTODON -> {
                val mastodon = details.newMicroBlogInstance(application, Mastodon::class.java)
                mastodon.authorizeFollowRequest(userKey.id)
                return@then mastodon.getAccount(userKey.id).toParcelable(details)
            }
            else -> {
                val twitter = details.newMicroBlogInstance(application, MicroBlog::class.java)
                return@then twitter.acceptFriendship(userKey.id).toParcelable(details,
                        profileImageSize = profileImageSize)
            }
        }
    }.success {
        Utils.setLastSeen(application, userKey, System.currentTimeMillis())
    }.toastOnResult(application) { user ->
        val nameFirst = preferences[nameFirstKey]
        return@toastOnResult application.getString(R.string.message_toast_accepted_users_follow_request,
                manager.getDisplayName(user, nameFirst))
    }.notifyOnResult(bus, FriendshipTaskEvent.Action.ACCEPT, accountKey, userKey)

    fun deny(accountKey: UserKey, userKey: UserKey): Promise<ParcelableUser, Exception>
            = notifyCreatePromise(bus, FriendshipTaskEvent.Action.DENY, accountKey, userKey)
            .thenGetAccount(application, accountKey).then { account ->
        when (account.type) {
            AccountType.FANFOU -> {
                val fanfou = account.newMicroBlogInstance(application, MicroBlog::class.java)
                return@then fanfou.denyFanfouFriendship(userKey.id).toParcelable(account,
                        profileImageSize = profileImageSize)
            }
            AccountType.MASTODON -> {
                val mastodon = account.newMicroBlogInstance(application, Mastodon::class.java)
                mastodon.rejectFollowRequest(userKey.id)
                return@then mastodon.getAccount(userKey.id).toParcelable(account)
            }
            else -> {
                val twitter = account.newMicroBlogInstance(application, MicroBlog::class.java)
                return@then twitter.denyFriendship(userKey.id).toParcelable(account,
                        profileImageSize = profileImageSize)
            }
        }
    }.success {
        Utils.setLastSeen(application, userKey, -1)
    }.toastOnResult(application) { user ->
        val nameFirst = preferences[nameFirstKey]
        return@toastOnResult application.getString(R.string.denied_users_follow_request,
                manager.getDisplayName(user, nameFirst))
    }.notifyOnResult(bus, FriendshipTaskEvent.Action.DENY, accountKey, userKey)

    fun create(accountKey: UserKey, userKey: UserKey, screenName: String?): Promise<ParcelableUser, Exception>
            = notifyCreatePromise(bus, FriendshipTaskEvent.Action.FOLLOW, accountKey, userKey)
            .thenGetAccount(application, accountKey).then { account ->
        when (account.type) {
            AccountType.FANFOU -> {
                val fanfou = account.newMicroBlogInstance(application, MicroBlog::class.java)
                return@then fanfou.createFanfouFriendship(userKey.id).toParcelable(account,
                        profileImageSize = profileImageSize)
            }
            AccountType.MASTODON -> {
                val mastodon = account.newMicroBlogInstance(application, Mastodon::class.java)
                if (account.key.host != userKey.host) {
                    if (screenName == null)
                        throw MicroBlogException("Screen name required to follow remote user")
                    return@then mastodon.followRemoteUser("$screenName@${userKey.host}")
                            .toParcelable(account)
                }
                mastodon.followUser(userKey.id)
                return@then mastodon.getAccount(userKey.id).toParcelable(account)
            }
            else -> {
                val twitter = account.newMicroBlogInstance(application, MicroBlog::class.java)
                return@then twitter.createFriendship(userKey.id).toParcelable(account,
                        profileImageSize = profileImageSize)
            }
        }
    }.then { user ->
        user.is_following = true
        Utils.setLastSeen(application, user.key, System.currentTimeMillis())
        return@then user
    }.toastOnResult(application) { user ->
        val nameFirst = preferences[nameFirstKey]
        return@toastOnResult if (user.is_protected) {
            application.getString(R.string.sent_follow_request_to_user,
                    manager.getDisplayName(user, nameFirst))
        } else {
            application.getString(R.string.followed_user,
                    manager.getDisplayName(user, nameFirst))
        }
    }.notifyOnResult(bus, FriendshipTaskEvent.Action.FOLLOW, accountKey, userKey)

    fun destroy(accountKey: UserKey, userKey: UserKey): Promise<ParcelableUser, Exception>
            = notifyCreatePromise(bus, FriendshipTaskEvent.Action.UNFOLLOW, accountKey, userKey)
            .thenGetAccount(application, accountKey).then { account ->
        when (account.type) {
            AccountType.FANFOU -> {
                val fanfou = account.newMicroBlogInstance(application, MicroBlog::class.java)
                return@then fanfou.destroyFanfouFriendship(userKey.id).toParcelable(account,
                        profileImageSize = profileImageSize)
            }
            AccountType.MASTODON -> {
                val mastodon = account.newMicroBlogInstance(application, Mastodon::class.java)
                mastodon.unfollowUser(userKey.id)
                return@then mastodon.getAccount(userKey.id).toParcelable(account)
            }
            else -> {
                val twitter = account.newMicroBlogInstance(application, MicroBlog::class.java)
                return@then twitter.destroyFriendship(userKey.id).toParcelable(account,
                        profileImageSize = profileImageSize)
            }
        }
    }.then { user ->
        user.is_following = false
        Utils.setLastSeen(application, user.key, -1)
        val where = Expression.and(Expression.equalsArgs(Statuses.ACCOUNT_KEY),
                Expression.or(Expression.equalsArgs(Statuses.USER_KEY),
                        Expression.equalsArgs(Statuses.RETWEETED_BY_USER_KEY)))
        val whereArgs = arrayOf(accountKey.toString(), userKey.toString(), userKey.toString())
        val resolver = application.contentResolver
        resolver.delete(Statuses.HomeTimeline.CONTENT_URI, where.sql, whereArgs)
        return@then user
    }.toastOnResult(application) { user ->
        val nameFirst = preferences[nameFirstKey]
        return@toastOnResult application.getString(R.string.unfollowed_user,
                manager.getDisplayName(user, nameFirst))
    }.notifyOnResult(bus, FriendshipTaskEvent.Action.UNFOLLOW, accountKey, userKey)

    companion object : ApplicationContextSingletonHolder<FriendshipPromises>(::FriendshipPromises) {

        private val tasks = mutableSetOf<ObjectId<UserKey>>()

        internal fun addTask(accountKey: UserKey, userKey: UserKey) {
            tasks.add(ObjectId(accountKey, userKey))
        }

        internal fun removeTask(accountKey: UserKey, userKey: UserKey) {
            tasks.remove(ObjectId(accountKey, userKey))
        }

        fun isRunning(accountKey: UserKey, userKey: UserKey) = tasks.any {
            it.accountKey == accountKey && it.id == userKey
        }
    }
}
