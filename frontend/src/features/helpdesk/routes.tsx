import type { RouteObject } from 'react-router'
import { HelpDeskIndex } from './HelpDeskIndex'

/** /app/helpdesk/* children (Tasks 8–10 add theirs). */
export const helpdeskChildren: RouteObject[] = [{ index: true, element: <HelpDeskIndex /> }]
