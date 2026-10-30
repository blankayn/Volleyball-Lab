# Android layout review

## Changes

- Assignments: search and clear controls appear before New drill, followed by
  status filters and results. Players uses the same search field and order.
- Player details: overview, recent shared sessions and assignments share one
  scrollable profile. The Overview / Sessions / Plan tab strip is removed.
- Coach lists preserve system side insets and provide consistent bottom space.
- Detail headers give long names the full content width on narrow screens and
  at larger text sizes. Metadata badges sit below the name.
- Sets/Reps and profile export controls use the available width and text scale
  to choose a row or a vertical stack. Stepper buttons are explicitly 48 dp.
- Profile photo actions wrap onto another row when necessary. Instruction fields
  grow with their text rather than using a fixed height.
- Coach metrics and My Coach shortcuts use the same responsive pair layout.
- Student skill cards separate text from artwork on narrow screens and at larger
  font sizes. They grow to fit their content and retain readable ink on pastel
  backgrounds in both themes.
- Shared section headings and coach detail headers keep text within the page.

## Verification

`ResponsiveLayoutDeviceTest` exercises actual UI components with fixture data at
320 dp and 600 dp widths and up to 160% text size. It checks search/action order,
aligned edges, enabled and disabled creation controls, 48 dp stepper buttons,
export callbacks, long names, metric stacking, all five skill descriptions, dark
theme rendering, and access to sessions and assignments without Player tabs.

Text checks inspect visible line bounds and ellipsis, rather than interpreting
unused paragraph wrapping width as clipped text. Fixture screenshots cover
search controls, large-text headers, metrics, stepper controls and skill cards.

Navigation, profile, My Coach and notification regressions are covered by
`TabNavigationTest`, `AccountMenuDeviceTest`, `MyCoachHubDeviceTest` and
`CoachWorkspaceDeviceTest`. These checks use fixture callbacks and do not modify
real coach or student records.
