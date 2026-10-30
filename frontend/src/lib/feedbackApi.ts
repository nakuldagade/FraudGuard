import type { FeedbackStats, ModelUsage, RegistryInfo } from '../feedbackTypes';
import { request } from './api';

export const fetchFeedbackStats = () => request<FeedbackStats>('/api/feedback/stats');
export const fetchModelUsage = () => request<ModelUsage>('/api/feedback/models');
export const fetchRegistry = () => request<RegistryInfo>('/api/feedback/registry');
