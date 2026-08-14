<script setup lang="ts">
import AppShell from '../../components/AppShell.vue'
</script>

<template>
  <AppShell title="Problems" subtitle="Problem list with platform filters, keywords and tags.">
    <section class="placeholder">
      <p class="placeholder__mark">Problems</p>
      <p>Problem list with platform filters, keywords and tags.</p>
      <p class="placeholder__hint">
        Placeholder page mounted by WP12 scaffolding. Page data will be fetched
        from the local ResourceHttpServer REST endpoints.
      </p>
    </section>
  </AppShell>
</template>

<style scoped>
.placeholder {
  color: var(--cq-muted);
  border: 1px dashed var(--cq-border);
  border-radius: 8px;
  padding: 24px;
  background: var(--cq-panel);
}

.placeholder__mark {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 650;
  color: var(--cq-fg);
}

.placeholder__hint {
  font-size: 12px;
}
</style>
