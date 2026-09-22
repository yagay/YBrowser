package com.yagay.YBrowser

/**
 * Dedicated YagaYHub browser entry.
 *
 * It intentionally has no task affinity so when YagaYHub launches it
 * without NEW_TASK it stays in the YagaYHub task stack, while still
 * reusing the complete YBrowser implementation and browser data.
 */
class YagaYHubEmbeddedActivity : MainActivity()
