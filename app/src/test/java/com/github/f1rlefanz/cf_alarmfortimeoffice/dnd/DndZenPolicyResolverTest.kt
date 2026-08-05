package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import android.service.notification.ZenPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class DndZenPolicyResolverTest {

    @Test
    fun `blockCalls=true ergibt allowCallsPeopleType NONE, false ergibt ANYONE`() {
        assertEquals(
            ZenPolicy.PEOPLE_TYPE_NONE,
            resolveDndZenPolicyIntent(DndPrefs.Policy(blockCalls = true)).allowCallsPeopleType
        )
        assertEquals(
            ZenPolicy.PEOPLE_TYPE_ANYONE,
            resolveDndZenPolicyIntent(DndPrefs.Policy(blockCalls = false)).allowCallsPeopleType
        )
    }

    @Test
    fun `blockMessages=true ergibt allowMessagesPeopleType NONE, false ergibt ANYONE`() {
        assertEquals(
            ZenPolicy.PEOPLE_TYPE_NONE,
            resolveDndZenPolicyIntent(DndPrefs.Policy(blockMessages = true)).allowMessagesPeopleType
        )
        assertEquals(
            ZenPolicy.PEOPLE_TYPE_ANYONE,
            resolveDndZenPolicyIntent(DndPrefs.Policy(blockMessages = false)).allowMessagesPeopleType
        )
    }

    @Test
    fun `blockConversations=true ergibt allowConversationsSenders NONE, false ergibt ANYONE`() {
        assertEquals(
            ZenPolicy.CONVERSATION_SENDERS_NONE,
            resolveDndZenPolicyIntent(DndPrefs.Policy(blockConversations = true)).allowConversationsSenders
        )
        assertEquals(
            ZenPolicy.CONVERSATION_SENDERS_ANYONE,
            resolveDndZenPolicyIntent(DndPrefs.Policy(blockConversations = false)).allowConversationsSenders
        )
    }

    @Test
    fun `blockReminders=true ergibt allowReminders false, false ergibt true`() {
        assertEquals(false, resolveDndZenPolicyIntent(DndPrefs.Policy(blockReminders = true)).allowReminders)
        assertEquals(true, resolveDndZenPolicyIntent(DndPrefs.Policy(blockReminders = false)).allowReminders)
    }

    @Test
    fun `blockEvents=true ergibt allowEvents false, false ergibt true`() {
        assertEquals(false, resolveDndZenPolicyIntent(DndPrefs.Policy(blockEvents = true)).allowEvents)
        assertEquals(true, resolveDndZenPolicyIntent(DndPrefs.Policy(blockEvents = false)).allowEvents)
    }

    @Test
    fun `blockAlarms=true ergibt allowAlarms false, false ergibt true`() {
        assertEquals(false, resolveDndZenPolicyIntent(DndPrefs.Policy(blockAlarms = true)).allowAlarms)
        assertEquals(true, resolveDndZenPolicyIntent(DndPrefs.Policy(blockAlarms = false)).allowAlarms)
    }

    @Test
    fun `blockMedia=true ergibt allowMedia false, false ergibt true`() {
        assertEquals(false, resolveDndZenPolicyIntent(DndPrefs.Policy(blockMedia = true)).allowMedia)
        assertEquals(true, resolveDndZenPolicyIntent(DndPrefs.Policy(blockMedia = false)).allowMedia)
    }

    @Test
    fun `blockSystem=true ergibt allowSystem false, false ergibt true`() {
        assertEquals(false, resolveDndZenPolicyIntent(DndPrefs.Policy(blockSystem = true)).allowSystem)
        assertEquals(true, resolveDndZenPolicyIntent(DndPrefs.Policy(blockSystem = false)).allowSystem)
    }
}
