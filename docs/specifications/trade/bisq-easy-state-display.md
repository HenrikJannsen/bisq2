# Bisq Easy trade state display

The trade's current protocol state is the authority for the desktop's trade step and phase indicator.

## Rules

1. The desktop shows the current state of the selected trade. A delayed notification of an earlier state must not
   replace a newer step or phase, including after a local user action.
2. A notification belonging to a previously selected trade must not update the selected trade's display.
3. After the trade display is deactivated, its subscriptions stop updating the display. Activating it again displays
   the current state and resumes updates without requiring another protocol message or a new trade selection.

## Rationale

Received messages can change the trade on a background thread, while user actions change it on the desktop's UI
thread. A background notification waits for the UI thread. A later local action can update the displayed step before
that notification runs. Displaying the earlier state afterward would restore an action which the trade has already
completed, making the trade appear stuck even though its protocol state advanced.

Switching trades or leaving the view can also occur before a queued notification runs. Removing a subscription does
not cancel notifications which already wait for the UI thread.

## Compatibility

These rules affect the desktop display only. They do not change protocol transitions, network messages or persisted
trade data.
