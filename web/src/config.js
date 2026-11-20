/** 部署基路径：vite base 为 /chatry/ 时，接口与路由都挂在该前缀下。 */
export const BASE_PATH = (import.meta.env.BASE_URL || '/').replace(/\/$/, '');
