/**
 * 页面渲染冒烟:任一页面 mount 后标题存在(任务要求至少一条,这里六页全过一遍)。
 * 同时覆盖 theme.ts 的 data-theme 切换通道。
 */
import { afterEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ProblemsApp from '../../src/pages/problems/App.vue'
import ContestApp from '../../src/pages/contest/App.vue'
import StatsApp from '../../src/pages/stats/App.vue'
import SearchApp from '../../src/pages/search/App.vue'
import ProblemViewApp from '../../src/pages/problem-view/App.vue'
import SubmissionsApp from '../../src/pages/submissions/App.vue'

const CASES = [
  ['problems', ProblemsApp, 'Problems'],
  ['contest', ContestApp, 'Contest'],
  ['stats', StatsApp, 'Statistics'],
  ['search', SearchApp, 'Search'],
  ['problem-view', ProblemViewApp, 'Problem View'],
  ['submissions', SubmissionsApp, 'Submissions'],
] as const

describe('页面渲染冒烟', () => {
  afterEach(() => {
    document.documentElement.removeAttribute('data-theme')
  })

  for (const [name, app, title] of CASES) {
    it(`${name} 页面 mount 后标题存在`, () => {
      const wrapper = mount(app)
      expect(wrapper.text()).toContain(title)
      expect(wrapper.find('h1').exists()).toBe(true)
    })
  }
})
