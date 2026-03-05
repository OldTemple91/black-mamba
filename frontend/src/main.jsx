import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import MainPage from './pages/MainPage.jsx'
import RouteListPage from './pages/RouteListPage.jsx'
import './index.css'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<MainPage />} />
        <Route path="/routes" element={<RouteListPage />} />
      </Routes>
    </BrowserRouter>
  </React.StrictMode>
)
