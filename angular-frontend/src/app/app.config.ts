import { ApplicationConfig } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { KI_MODELS_API_BASE } from '@dataclub/ki-models-ui';

export const appConfig: ApplicationConfig = {
  providers: [
    provideHttpClient(),
    // ki-models-ui Library: alle CRUD-Calls gehen direkt gegen /api/cascade-* —
    // Switcher's Endpoints sind unter /api (kein /admin-Prefix).
    // Library übersetzt das automatisch zu /api/ai-models — daher Mapping
    // via Backend (cascade-models → ai-models). Da Phase K aber bewusst die
    // alten Pfade `/api/cascade-models` etc. beibehalten hat, liefern wir hier
    // einen Custom-Endpoint-Mapper im Service oder die Backend-Endpoints
    // werden in einem Folge-Commit zu /api/ai-models, /api/api-keys,
    // /api/cascade-config umbenannt.
    //
    // Pragmatisch für L.4 MVP: setze BASE='/api' UND erweitere/aliase die
    // Backend-Endpoints (siehe ApiController). Im PR mitgeliefert.
    { provide: KI_MODELS_API_BASE, useValue: '/api' },
  ],
};
