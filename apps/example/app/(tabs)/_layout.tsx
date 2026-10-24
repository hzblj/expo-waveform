import {Tabs} from '@hzblj/kara-ui/router'

export default function TabLayout() {
  return (
    <Tabs>
      <Tabs.Screen icon="radio" name="(radio)" selectedIcon="radio-filled" title="Radio" />
      <Tabs.Screen icon="palette" name="styles" selectedIcon="palette-filled" title="Styles" />
      <Tabs.Screen icon="mic" name="live" selectedIcon="mic-filled" title="Pit wall" />
      <Tabs.Screen icon="science" name="lab" selectedIcon="science-filled" title="Lab" />
    </Tabs>
  )
}
